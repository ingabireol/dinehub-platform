package com.dinehub.kitchen.messaging;

import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.common.messaging.IdempotencyService;
import com.dinehub.kitchen.service.KitchenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The kitchen reacts to payment and cancellation.
 */
@Component
public class KitchenEventListener {

    private static final Logger log = LoggerFactory.getLogger(KitchenEventListener.class);

    private final KitchenService kitchenService;
    private final IdempotencyService idempotency;

    public KitchenEventListener(KitchenService kitchenService, IdempotencyService idempotency) {
        this.kitchenService = kitchenService;
        this.idempotency = idempotency;
    }

    /**
     * Notes what the order contains. Deliberately does not create a ticket —
     * the kitchen must not start cooking before the payment clears.
     */
    @RabbitListener(queues = DomainEvents.Q_KITCHEN_ORDER_PLACED)
    public void onOrderPlaced(EventPayloads.OrderPlaced event) {
        withTrace(event.meta().traceId(), () ->
                idempotency.runOnce(event.meta().eventId(), DomainEvents.ORDER_PLACED, () ->
                        kitchenService.rememberOrderContents(event.orderId(), event.customerId(),
                                event.itemsSummary(), event.itemCount())));
    }

    @RabbitListener(queues = DomainEvents.Q_KITCHEN_PAYMENT)
    public void onPaymentCompleted(EventPayloads.PaymentCompleted event) {
        withTrace(event.meta().traceId(), () ->
                idempotency.runOnce(event.meta().eventId(), DomainEvents.PAYMENT_COMPLETED, () -> {
                    log.info("Order {} is paid — queueing it for the kitchen", event.orderId());
                    kitchenService.createTicketForPaidOrder(
                            event.orderId(), event.customerId(), event.amount(), 0);
                }));
    }

    @RabbitListener(queues = DomainEvents.Q_KITCHEN_ORDER_CANCELLED)
    public void onOrderCancelled(EventPayloads.OrderCancelled event) {
        withTrace(event.meta().traceId(), () ->
                idempotency.runOnce(event.meta().eventId(), DomainEvents.ORDER_CANCELLED, () -> {
                    log.info("Order {} cancelled — removing it from the board", event.orderId());
                    kitchenService.cancelTicketForOrder(event.orderId());
                }));
    }

    private void withTrace(String traceId, Runnable work) {
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        try {
            work.run();
        } finally {
            MDC.remove("traceId");
        }
    }
}
