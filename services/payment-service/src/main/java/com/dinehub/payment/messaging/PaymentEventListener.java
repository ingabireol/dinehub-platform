package com.dinehub.payment.messaging;

import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.common.messaging.IdempotencyService;
import com.dinehub.payment.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Charges for an order as soon as it is placed.
 *
 * <p>The idempotency guard matters more here than anywhere else in the platform:
 * a redelivered {@code order.placed} that is processed twice is a customer
 * charged twice.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PaymentService paymentService;
    private final IdempotencyService idempotency;

    public PaymentEventListener(PaymentService paymentService, IdempotencyService idempotency) {
        this.paymentService = paymentService;
        this.idempotency = idempotency;
    }

    @RabbitListener(queues = DomainEvents.Q_PAYMENT_ORDER_PLACED)
    public void onOrderPlaced(EventPayloads.OrderPlaced event) {
        String traceId = event.meta().traceId();
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        try {
            idempotency.runOnce(event.meta().eventId(), DomainEvents.ORDER_PLACED, () -> {
                log.info("Charging order {} for {}", event.orderId(), event.totalAmount());
                paymentService.processOrderPlaced(event.orderId(), event.customerId(),
                        event.customerEmail(), event.totalAmount());
            });
        } finally {
            MDC.remove("traceId");
        }
    }
}
