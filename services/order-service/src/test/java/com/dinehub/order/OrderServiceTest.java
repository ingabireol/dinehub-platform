package com.dinehub.order;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.common.security.Roles;
import com.dinehub.order.client.MenuClient;
import com.dinehub.order.dto.OrderDtos;
import com.dinehub.order.entity.Order;
import com.dinehub.order.entity.OrderItem;
import com.dinehub.order.entity.OrderStatus;
import com.dinehub.order.repository.OrderRepository;
import com.dinehub.order.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orders;

    @Mock
    private MenuClient menuClient;

    @Mock
    private RabbitTemplate rabbit;

    private OrderService orderService;
    private MeterRegistry meterRegistry;

    private UUID customerId;
    private UUID tilapiaId;
    private UUID juiceId;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        orderService = new OrderService(orders, menuClient, rabbit, meterRegistry);
        customerId = UUID.randomUUID();
        tilapiaId = UUID.randomUUID();
        juiceId = UUID.randomUUID();
    }

    private OrderDtos.PricedItem priced(UUID id, String name, String price, boolean available) {
        return new OrderDtos.PricedItem(id, name, new BigDecimal(price), available, 20);
    }

    @Nested
    @DisplayName("placing an order")
    class Placing {

        @Test
        @DisplayName("prices the basket and returns the order")
        void placesOrder() {
            when(menuClient.priceItems(anyList())).thenReturn(List.of(
                    priced(tilapiaId, "Grilled Tilapia", "12.50", true),
                    priced(juiceId, "Fresh Juice", "3.00", true)));
            when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            var response = orderService.placeOrder(customerId, "c@dinehub.local",
                    new OrderDtos.CreateOrderRequest(List.of(
                            new OrderDtos.OrderLineRequest(tilapiaId, 2),
                            new OrderDtos.OrderLineRequest(juiceId, 1)),
                            "12 Example Street", null));

            // 2 × 12.50 + 1 × 3.00 = 28.00
            assertThat(response.totalAmount()).isEqualByComparingTo("28.00");
            assertThat(response.status()).isEqualTo("PLACED");
            assertThat(response.items()).hasSize(2);
        }

        @Test
        @DisplayName("snapshots the name and price onto the order")
        void snapshotsPrices() {
            // The whole reason order-service calls menu-service synchronously.
            // A later price change must not rewrite what this customer paid.
            when(menuClient.priceItems(anyList())).thenReturn(List.of(
                    priced(tilapiaId, "Grilled Tilapia", "12.50", true)));
            when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            var response = orderService.placeOrder(customerId, "c@dinehub.local",
                    new OrderDtos.CreateOrderRequest(
                            List.of(new OrderDtos.OrderLineRequest(tilapiaId, 1)), null, null));

            assertThat(response.items().getFirst().itemName()).isEqualTo("Grilled Tilapia");
            assertThat(response.items().getFirst().unitPrice()).isEqualByComparingTo("12.50");
        }

        @Test
        @DisplayName("collapses duplicate lines for the same item into one")
        void collapsesDuplicateLines() {
            // Two lines for the same dish produce a confusing receipt and a
            // confusing kitchen ticket. Adding the same dish twice means
            // quantity 2.
            when(menuClient.priceItems(anyList())).thenReturn(List.of(
                    priced(tilapiaId, "Grilled Tilapia", "12.50", true)));
            when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            var response = orderService.placeOrder(customerId, "c@dinehub.local",
                    new OrderDtos.CreateOrderRequest(List.of(
                            new OrderDtos.OrderLineRequest(tilapiaId, 1),
                            new OrderDtos.OrderLineRequest(tilapiaId, 2)), null, null));

            assertThat(response.items()).hasSize(1);
            assertThat(response.items().getFirst().quantity()).isEqualTo(3);
            assertThat(response.totalAmount()).isEqualByComparingTo("37.50");
        }

        @Test
        @DisplayName("refuses an order containing an item that is not on the menu")
        void refusesUnknownItem() {
            // Silently dropping it would charge the customer for a different
            // basket than the one they submitted.
            UUID ghost = UUID.randomUUID();
            when(menuClient.priceItems(anyList())).thenReturn(List.of(
                    priced(tilapiaId, "Grilled Tilapia", "12.50", true)));

            assertThatThrownBy(() -> orderService.placeOrder(customerId, "c@dinehub.local",
                    new OrderDtos.CreateOrderRequest(List.of(
                            new OrderDtos.OrderLineRequest(tilapiaId, 1),
                            new OrderDtos.OrderLineRequest(ghost, 1)), null, null)))
                    .isInstanceOf(ApiExceptions.BadRequestException.class)
                    .hasMessageContaining("not on the menu");

            verify(orders, never()).save(any());
        }

        @Test
        @DisplayName("refuses an order containing a sold-out item, naming it")
        void refusesUnavailableItem() {
            when(menuClient.priceItems(anyList())).thenReturn(List.of(
                    priced(tilapiaId, "Grilled Tilapia", "12.50", false)));

            assertThatThrownBy(() -> orderService.placeOrder(customerId, "c@dinehub.local",
                    new OrderDtos.CreateOrderRequest(
                            List.of(new OrderDtos.OrderLineRequest(tilapiaId, 1)), null, null)))
                    .isInstanceOf(ApiExceptions.BadRequestException.class)
                    .hasMessageContaining("Grilled Tilapia")
                    .hasMessageContaining("unavailable");
        }

        @Test
        @DisplayName("publishes order.placed")
        void publishesOrderPlaced() {
            when(menuClient.priceItems(anyList())).thenReturn(List.of(
                    priced(tilapiaId, "Grilled Tilapia", "12.50", true)));
            when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            orderService.placeOrder(customerId, "c@dinehub.local",
                    new OrderDtos.CreateOrderRequest(
                            List.of(new OrderDtos.OrderLineRequest(tilapiaId, 1)), null, null));

            ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
            verify(rabbit).convertAndSend(anyString(), routingKey.capture(), any(Object.class));
            assertThat(routingKey.getValue()).isEqualTo("order.placed");
        }

        @Test
        @DisplayName("the order survives the broker being down")
        void survivesBrokerOutage() {
            // The order is already committed. Rolling it back because RabbitMQ
            // blinked would lose the customer's order; losing the event is
            // recoverable and visible.
            when(menuClient.priceItems(anyList())).thenReturn(List.of(
                    priced(tilapiaId, "Grilled Tilapia", "12.50", true)));
            when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
            doThrow(new AmqpException("broker down"))
                    .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

            var response = orderService.placeOrder(customerId, "c@dinehub.local",
                    new OrderDtos.CreateOrderRequest(
                            List.of(new OrderDtos.OrderLineRequest(tilapiaId, 1)), null, null));

            assertThat(response.status()).isEqualTo("PLACED");
            verify(orders).save(any(Order.class));
        }

        @Test
        @DisplayName("records the business metric")
        void recordsMetrics() {
            when(menuClient.priceItems(anyList())).thenReturn(List.of(
                    priced(tilapiaId, "Grilled Tilapia", "12.50", true)));
            when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            orderService.placeOrder(customerId, "c@dinehub.local",
                    new OrderDtos.CreateOrderRequest(
                            List.of(new OrderDtos.OrderLineRequest(tilapiaId, 1)), null, null));

            assertThat(meterRegistry.counter("dinehub.orders.placed").count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("access control")
    class AccessControl {

        private Order otherCustomersOrder() {
            var order = new Order(UUID.randomUUID(), "someone@else.local", null, null);
            order.addItem(new OrderItem(tilapiaId, "Tilapia", new BigDecimal("12.50"), 1));
            return order;
        }

        @Test
        @DisplayName("a customer cannot read another customer's order")
        void customerCannotReadAnothersOrder() {
            var order = otherCustomersOrder();
            when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

            // 404 rather than 403: confirming it exists tells an attacker
            // something they should not learn from a probe.
            assertThatThrownBy(() ->
                    orderService.getOrder(order.getId(), customerId, Roles.CUSTOMER))
                    .isInstanceOf(ApiExceptions.NotFoundException.class);
        }

        @Test
        @DisplayName("kitchen staff may read any order")
        void kitchenCanReadAnyOrder() {
            var order = otherCustomersOrder();
            when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

            var response = orderService.getOrder(order.getId(), customerId, Roles.KITCHEN);

            assertThat(response.id()).isEqualTo(order.getId());
        }

        @Test
        @DisplayName("a customer may read their own order")
        void customerCanReadOwnOrder() {
            var order = new Order(customerId, "c@dinehub.local", null, null);
            when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

            var response = orderService.getOrder(order.getId(), customerId, Roles.CUSTOMER);

            assertThat(response.customerId()).isEqualTo(customerId);
        }

        @Test
        @DisplayName("a customer cannot cancel another customer's order")
        void customerCannotCancelAnothersOrder() {
            var order = otherCustomersOrder();
            when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

            assertThatThrownBy(() ->
                    orderService.cancelOrder(order.getId(), customerId, Roles.CUSTOMER, "nope"))
                    .isInstanceOf(ApiExceptions.NotFoundException.class);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        }
    }

    @Nested
    @DisplayName("status changes from events")
    class StatusFromEvents {

        private Order placedOrder() {
            var order = new Order(customerId, "c@dinehub.local", null, null);
            order.addItem(new OrderItem(tilapiaId, "Tilapia", new BigDecimal("12.50"), 1));
            return order;
        }

        @Test
        @DisplayName("a payment event moves PLACED to PAID")
        void appliesPaidStatus() {
            var order = placedOrder();
            when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));
            when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            boolean applied = orderService.applyStatusFromEvent(order.getId(), OrderStatus.PAID);

            assertThat(applied).isTrue();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        }

        @Test
        @DisplayName("a redelivered event is ignored rather than failing")
        void ignoresRedeliveredEvent() {
            // Dead-lettering a message that was in fact already handled would
            // fill the DLQ with things nobody needs to act on.
            var order = placedOrder();
            order.transitionTo(OrderStatus.PAID);
            when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

            boolean applied = orderService.applyStatusFromEvent(order.getId(), OrderStatus.PAID);

            assertThat(applied).isFalse();
            verify(orders, never()).save(any());
        }

        @Test
        @DisplayName("a late payment event cannot resurrect a cancelled order")
        void cancelledOrderIsNotResurrected() {
            var order = placedOrder();
            order.cancel("Changed my mind");
            when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

            boolean applied = orderService.applyStatusFromEvent(order.getId(), OrderStatus.PAID);

            assertThat(applied).isFalse();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("an event for an unknown order is ignored")
        void ignoresUnknownOrder() {
            UUID ghost = UUID.randomUUID();
            when(orders.findWithItemsById(ghost)).thenReturn(Optional.empty());

            assertThat(orderService.applyStatusFromEvent(ghost, OrderStatus.PAID)).isFalse();
        }

        @Test
        @DisplayName("a failed payment cancels the order with the reason")
        void failedPaymentCancelsOrder() {
            var order = placedOrder();
            when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));
            when(orders.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            orderService.cancelFromFailedPayment(order.getId(), "Card declined");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancellationReason()).contains("Card declined");
        }

        @Test
        @DisplayName("a failed payment for an already terminal order does nothing")
        void failedPaymentOnTerminalOrderIsIgnored() {
            var order = placedOrder();
            order.cancel("Customer cancelled first");
            when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

            orderService.cancelFromFailedPayment(order.getId(), "Card declined");

            assertThat(order.getCancellationReason()).isEqualTo("Customer cancelled first");
            verify(orders, never()).save(any());
        }
    }

    @Test
    @DisplayName("cancelling after preparation has started is a 409")
    void refusesLateCancellation() {
        var order = new Order(customerId, "c@dinehub.local", null, null);
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.PREPARING);
        when(orders.findWithItemsById(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() ->
                orderService.cancelOrder(order.getId(), customerId, Roles.CUSTOMER, "too late"))
                .isInstanceOf(ApiExceptions.ConflictException.class);
    }
}
