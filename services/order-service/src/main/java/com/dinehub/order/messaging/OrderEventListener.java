package com.dinehub.order.messaging;

import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.common.messaging.IdempotencyService;
import com.dinehub.order.entity.OrderStatus;
import com.dinehub.order.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to events from payment-service and kitchen-service.
 *
 * <p>Every handler is wrapped in {@link IdempotencyService}. RabbitMQ guarantees
 * at-least-once delivery, so each of these <em>will</em> see a duplicate
 * eventually — most often during a redeploy, when unacknowledged messages are
 * redelivered to the new pod.
 *
 * <p>A handler that throws causes the message to be retried and eventually
 * dead-lettered. A handler that finds the transition illegal returns quietly
 * instead: that is a late or out-of-order event, not a failure, and
 * dead-lettering it would fill the DLQ with messages nobody needs to act on.
 */
@Component
public class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final OrderService orderService;
    private final IdempotencyService idempotency;

    public OrderEventListener(OrderService orderService, IdempotencyService idempotency) {
        this.orderService = orderService;
        this.idempotency = idempotency;
    }

    @RabbitListener(queues = DomainEvents.Q_ORDER_PAYMENT)
    public void onPaymentResult(EventPayloads.PaymentCompleted event) {
        withTrace(event.meta(), () ->
                idempotency.runOnce(event.meta().eventId(), DomainEvents.PAYMENT_COMPLETED, () -> {
                    log.info("Payment {} completed for order {}", event.paymentId(), event.orderId());
                    orderService.applyStatusFromEvent(event.orderId(), OrderStatus.PAID);
                }));
    }

    @RabbitListener(queues = DomainEvents.Q_ORDER_PAYMENT + ".failed")
    public void onPaymentFailed(EventPayloads.PaymentFailed event) {
        withTrace(event.meta(), () ->
                idempotency.runOnce(event.meta().eventId(), DomainEvents.PAYMENT_FAILED, () -> {
                    log.warn("Payment {} failed for order {}: {}",
                            event.paymentId(), event.orderId(), event.reason());
                    orderService.cancelFromFailedPayment(event.orderId(), event.reason());
                }));
    }

    @RabbitListener(queues = DomainEvents.Q_ORDER_KITCHEN)
    public void onKitchenStatus(EventPayloads.KitchenStatusUpdated event) {
        withTrace(event.meta(), () ->
                idempotency.runOnce(event.meta().eventId(), DomainEvents.KITCHEN_STATUS_UPDATED, () -> {
                    OrderStatus next;
                    try {
                        next = OrderStatus.valueOf(event.status());
                    } catch (IllegalArgumentException e) {
                        // An unknown status is a contract mismatch between
                        // services. Dead-letter it so somebody looks, rather
                        // than discarding it silently.
                        throw new IllegalStateException(
                                "kitchen-service sent an unknown status: " + event.status(), e);
                    }
                    log.info("Kitchen moved order {} to {}", event.orderId(), next);
                    orderService.applyStatusFromEvent(event.orderId(), next);
                }));
    }

    /**
     * Carries the trace id from the publishing service onto this service's log
     * lines, so one id follows a request across every hop including the
     * asynchronous ones.
     */
    private void withTrace(EventPayloads.EventMeta meta, Runnable work) {
        String traceId = meta.traceId();
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
