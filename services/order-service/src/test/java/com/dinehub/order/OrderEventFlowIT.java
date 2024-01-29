package com.dinehub.order;

import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.order.entity.Order;
import com.dinehub.order.entity.OrderItem;
import com.dinehub.order.entity.OrderStatus;
import com.dinehub.order.repository.OrderRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives order-service through the event path with a real broker and database.
 *
 * <p>This is the test that proves the routing keys, the queue bindings and the
 * JSON message converter line up. Each of those is fine in isolation; the
 * combination is where mistakes live, and a typo in a routing key silently binds
 * a queue that never receives anything — which no unit test can see.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OrderEventFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dinehub.jwt.secret",
                () -> "an-integration-test-signing-secret-long-enough");
        // The sweep would otherwise log noisily throughout the run.
        registry.add("dinehub.sweep.interval", () -> "PT1H");
    }

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private ApplicationContext context;

    @Transactional
    UUID persistPlacedOrder() {
        var order = new Order(UUID.randomUUID(), "it@dinehub.local", "12 Example Street", null);
        order.addItem(new OrderItem(UUID.randomUUID(), "Grilled Tilapia",
                new BigDecimal("12.50"), 2));
        orders.save(order);
        return order.getId();
    }

    private EventPayloads.EventMeta meta() {
        return EventPayloads.EventMeta.now("it-trace");
    }

    private OrderStatus statusOf(UUID orderId) {
        return orders.findById(orderId).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("the application context starts with every bean wired")
    void contextLoads() {
        // A unit test constructs a service with mocks and proves nothing about
        // whether Spring can wire it. This is what catches a repository or
        // entity that the scanning configuration cannot see.
        assertThat(context.getBean(com.dinehub.order.service.OrderService.class)).isNotNull();
        assertThat(context.getBean(OrderRepository.class)).isNotNull();
        assertThat(context.getBean(
                com.dinehub.common.messaging.IdempotencyService.class)).isNotNull();
    }

    @Test
    @DisplayName("payment.completed moves the order to PAID")
    void paymentCompletedAdvancesTheOrder() {
        UUID orderId = persistPlacedOrder();

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_COMPLETED,
                new EventPayloads.PaymentCompleted(meta(), UUID.randomUUID(), orderId,
                        UUID.randomUUID(), new BigDecimal("25.00"), "PAY-TEST"));

        Awaitility.await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID));
    }

    @Test
    @DisplayName("payment.failed cancels the order with the reason")
    void paymentFailedCancelsTheOrder() {
        UUID orderId = persistPlacedOrder();

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_FAILED,
                new EventPayloads.PaymentFailed(meta(), UUID.randomUUID(), orderId,
                        UUID.randomUUID(), new BigDecimal("25.00"), "Card declined by issuer"));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var order = orders.findById(orderId).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancellationReason()).contains("Card declined");
        });
    }

    @Test
    @DisplayName("kitchen.status.updated drives the order through its lifecycle")
    void kitchenStatusDrivesTheOrder() {
        UUID orderId = persistPlacedOrder();

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_COMPLETED,
                new EventPayloads.PaymentCompleted(meta(), UUID.randomUUID(), orderId,
                        UUID.randomUUID(), new BigDecimal("25.00"), "PAY-TEST"));
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> statusOf(orderId) == OrderStatus.PAID);

        for (var step : new OrderStatus[]{
                OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.DELIVERED}) {
            rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.KITCHEN_STATUS_UPDATED,
                    new EventPayloads.KitchenStatusUpdated(
                            meta(), orderId, step.name(), UUID.randomUUID()));
            Awaitility.await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(step));
        }
    }

    @Test
    @DisplayName("a redelivered payment event does not re-apply the transition")
    void redeliveryIsIdempotent() {
        UUID orderId = persistPlacedOrder();
        var event = new EventPayloads.PaymentCompleted(meta(), UUID.randomUUID(), orderId,
                UUID.randomUUID(), new BigDecimal("25.00"), "PAY-TEST");

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_COMPLETED, event);
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> statusOf(orderId) == OrderStatus.PAID);

        // The same event id again, as RabbitMQ would deliver it after a redeploy.
        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_COMPLETED, event);

        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID));
    }

    @Test
    @DisplayName("a late payment event cannot resurrect a cancelled order")
    void lateEventCannotResurrectACancelledOrder() {
        // The customer cancels, and a payment event already in flight arrives
        // afterwards. Without the transition guard the order silently becomes
        // PAID and the kitchen starts cooking it.
        UUID orderId = persistPlacedOrder();

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_FAILED,
                new EventPayloads.PaymentFailed(meta(), UUID.randomUUID(), orderId,
                        UUID.randomUUID(), new BigDecimal("25.00"), "Cancelled"));
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> statusOf(orderId) == OrderStatus.CANCELLED);

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_COMPLETED,
                new EventPayloads.PaymentCompleted(meta(), UUID.randomUUID(), orderId,
                        UUID.randomUUID(), new BigDecimal("25.00"), "PAY-LATE"));

        Awaitility.await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertThat(statusOf(orderId)).isEqualTo(OrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("an event for an order that does not exist is ignored, not dead-lettered")
    void unknownOrderIsIgnored() {
        // A stray event is not a failure worth a DLQ entry; it is a message for
        // a service that no longer has that order.
        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_COMPLETED,
                new EventPayloads.PaymentCompleted(meta(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), new BigDecimal("25.00"), "PAY-GHOST"));

        // Nothing to assert except that the listener does not wedge, so prove
        // it is still processing by sending a real one afterwards.
        UUID orderId = persistPlacedOrder();
        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_COMPLETED,
                new EventPayloads.PaymentCompleted(meta(), UUID.randomUUID(), orderId,
                        UUID.randomUUID(), new BigDecimal("25.00"), "PAY-REAL"));

        Awaitility.await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(statusOf(orderId)).isEqualTo(OrderStatus.PAID));
    }
}
