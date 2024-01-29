package com.dinehub.order;

import com.dinehub.common.events.EventPayloads;
import com.dinehub.common.messaging.IdempotencyService;
import com.dinehub.order.entity.OrderStatus;
import com.dinehub.order.messaging.OrderEventListener;
import com.dinehub.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderEventListenerTest {

    @Mock
    private OrderService orderService;

    @Mock
    private IdempotencyService idempotency;

    private OrderEventListener listener;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        listener = new OrderEventListener(orderService, idempotency);
        orderId = UUID.randomUUID();
    }

    /** Makes the idempotency guard run the work, as it would on first delivery. */
    private void allowThrough() {
        doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return true;
        }).when(idempotency).runOnce(any(UUID.class), anyString(), any(Runnable.class));
    }

    /** Makes the idempotency guard skip the work, as it would on a redelivery. */
    private void blockAsDuplicate() {
        when(idempotency.runOnce(any(UUID.class), anyString(), any(Runnable.class)))
                .thenReturn(false);
    }

    private EventPayloads.EventMeta meta() {
        return EventPayloads.EventMeta.now("trace-abc");
    }

    @Test
    @DisplayName("a completed payment moves the order to PAID")
    void paymentCompletedMovesOrderToPaid() {
        allowThrough();

        listener.onPaymentResult(new EventPayloads.PaymentCompleted(
                meta(), UUID.randomUUID(), orderId, UUID.randomUUID(),
                new BigDecimal("28.00"), "ref-1"));

        verify(orderService).applyStatusFromEvent(orderId, OrderStatus.PAID);
    }

    @Test
    @DisplayName("a redelivered payment event does no work twice")
    void redeliveredPaymentIsSkipped() {
        // The failure this prevents: RabbitMQ redelivers on a redeploy and the
        // order is processed again. For a payment that means charging twice.
        blockAsDuplicate();

        listener.onPaymentResult(new EventPayloads.PaymentCompleted(
                meta(), UUID.randomUUID(), orderId, UUID.randomUUID(),
                new BigDecimal("28.00"), "ref-1"));

        verify(orderService, never()).applyStatusFromEvent(any(), any());
    }

    @Test
    @DisplayName("a failed payment cancels the order with the reason")
    void paymentFailedCancelsOrder() {
        allowThrough();

        listener.onPaymentFailed(new EventPayloads.PaymentFailed(
                meta(), UUID.randomUUID(), orderId, UUID.randomUUID(),
                new BigDecimal("28.00"), "Card declined"));

        verify(orderService).cancelFromFailedPayment(orderId, "Card declined");
    }

    @Test
    @DisplayName("a kitchen status update is applied")
    void kitchenStatusIsApplied() {
        allowThrough();

        listener.onKitchenStatus(new EventPayloads.KitchenStatusUpdated(
                meta(), orderId, "PREPARING", UUID.randomUUID()));

        verify(orderService).applyStatusFromEvent(orderId, OrderStatus.PREPARING);
    }

    @Test
    @DisplayName("an unknown status from kitchen-service is dead-lettered, not discarded")
    void unknownStatusThrows() {
        // A status this service does not recognise is a contract mismatch
        // between two deployed versions. Throwing sends it to the DLQ where
        // somebody looks at it; swallowing it would lose the order silently.
        allowThrough();

        assertThatThrownBy(() -> listener.onKitchenStatus(
                new EventPayloads.KitchenStatusUpdated(
                        meta(), orderId, "REHEATING", UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown status")
                .hasMessageContaining("REHEATING");
    }

    @Test
    @DisplayName("every handler goes through the idempotency guard")
    void allHandlersAreGuarded() {
        // If a handler is ever added without the guard, this is what notices.
        allowThrough();

        listener.onPaymentResult(new EventPayloads.PaymentCompleted(
                meta(), UUID.randomUUID(), orderId, UUID.randomUUID(),
                BigDecimal.ONE, "r"));
        listener.onPaymentFailed(new EventPayloads.PaymentFailed(
                meta(), UUID.randomUUID(), orderId, UUID.randomUUID(),
                BigDecimal.ONE, "r"));
        listener.onKitchenStatus(new EventPayloads.KitchenStatusUpdated(
                meta(), orderId, "READY", UUID.randomUUID()));

        verify(idempotency, org.mockito.Mockito.times(3))
                .runOnce(any(UUID.class), anyString(), any(Runnable.class));
    }
}
