package com.dinehub.payment;

import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.payment.entity.Payment;
import com.dinehub.payment.repository.PaymentRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * Exercises the real message path: publish {@code order.placed} to a real
 * RabbitMQ, and assert that the listener consumes it, charges, and records the
 * payment in a real PostgreSQL.
 *
 * <p>This is the test that proves the queue bindings, the JSON converter and the
 * idempotency ledger actually work together. Each is fine in isolation and the
 * combination is where the mistakes live — a routing key typo binds a queue that
 * never receives anything, and no unit test can see that.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class PaymentIntegrationIT {

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
        // Deterministic: this test is about the message path, not about the
        // simulated decline logic, which has its own unit tests.
        registry.add("dinehub.payment.failure-rate", () -> "0.0");
        registry.add("dinehub.payment.simulated-latency-ms", () -> "0");
    }

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private PaymentRepository payments;

    private EventPayloads.OrderPlaced orderPlaced(UUID orderId, BigDecimal amount) {
        return new EventPayloads.OrderPlaced(
                EventPayloads.EventMeta.now("it-trace"),
                orderId, UUID.randomUUID(), "customer@dinehub.local", amount, 2);
    }

    @Test
    @DisplayName("an order.placed event results in a recorded payment")
    void consumesOrderPlacedAndCharges() {
        UUID orderId = UUID.randomUUID();

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.ORDER_PLACED,
                orderPlaced(orderId, new BigDecimal("28.00")));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var payment = payments.findByOrderId(orderId);
            assertThat(payment).isPresent();
            assertThat(payment.get().isSuccessful()).isTrue();
            assertThat(payment.get().getAmount()).isEqualByComparingTo("28.00");
        });
    }

    @Test
    @DisplayName("the same event delivered twice charges once")
    void isIdempotentAcrossRedelivery() {
        // The exact failure RabbitMQ's at-least-once guarantee produces on a
        // redeploy. Two charges for one order is the expensive outcome.
        UUID orderId = UUID.randomUUID();
        var event = orderPlaced(orderId, new BigDecimal("15.00"));

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.ORDER_PLACED, event);
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> payments.findByOrderId(orderId).isPresent());

        // Same event id, redelivered.
        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.ORDER_PLACED, event);

        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(payments.findAll().stream()
                        .filter(p -> p.getOrderId().equals(orderId))
                        .count()).isEqualTo(1L));
    }

    @Test
    @DisplayName("two different events for the same order still charge once")
    void uniqueConstraintPreventsDoubleCharge() {
        // Different event ids, so the idempotency ledger does not catch it. The
        // unique constraint on order_id is the second line of defence, and this
        // is the test that proves it is actually there.
        UUID orderId = UUID.randomUUID();

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.ORDER_PLACED,
                orderPlaced(orderId, new BigDecimal("22.00")));
        Awaitility.await().atMost(Duration.ofSeconds(15))
                .until(() -> payments.findByOrderId(orderId).isPresent());

        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.ORDER_PLACED,
                orderPlaced(orderId, new BigDecimal("22.00")));

        Awaitility.await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(payments.findAll().stream()
                        .filter(p -> p.getOrderId().equals(orderId))
                        .count()).isEqualTo(1L));
    }

    @Test
    @DisplayName("the reserved test amount produces a recorded failure")
    void declinedPaymentIsRecordedWithAReason() {
        UUID orderId = UUID.randomUUID();

        // .13 always declines, whatever the configured rate.
        rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.ORDER_PLACED,
                orderPlaced(orderId, new BigDecimal("28.13")));

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var payment = payments.findByOrderId(orderId);
            assertThat(payment).isPresent();
            assertThat(payment.get().getStatus()).isEqualTo(Payment.Status.FAILED);
            assertThat(payment.get().getFailureReason()).isNotBlank();
        });
    }
}
