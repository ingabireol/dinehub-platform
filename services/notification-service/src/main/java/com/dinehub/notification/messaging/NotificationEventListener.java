package com.dinehub.notification.messaging;

import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.common.messaging.IdempotencyService;
import com.dinehub.notification.entity.Notification;
import com.dinehub.notification.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Turns domain events into something a customer can read.
 *
 * <p>The wording is the entire value of this service, so it lives here rather
 * than being assembled in the UI: a customer should be told "your order is on
 * its way", not shown a status enum.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notificationService;
    private final IdempotencyService idempotency;

    public NotificationEventListener(NotificationService notificationService,
                                     IdempotencyService idempotency) {
        this.notificationService = notificationService;
        this.idempotency = idempotency;
    }

    @RabbitListener(queues = DomainEvents.Q_NOTIFICATION_USER_REGISTERED)
    public void onUserRegistered(EventPayloads.UserRegistered event) {
        withTrace(event.meta().traceId(), () ->
                idempotency.runOnce(event.meta().eventId(), DomainEvents.USER_REGISTERED, () ->
                        notificationService.record(
                                event.userId(),
                                Notification.Type.WELCOME,
                                "Welcome to DineHub",
                                "Hello %s — your account is ready. Have a look at today's menu."
                                        .formatted(firstName(event.fullName())),
                                null)));
    }

    @RabbitListener(queues = DomainEvents.Q_NOTIFICATION_ORDER_STATUS)
    public void onOrderStatusChanged(EventPayloads.OrderStatusChanged event) {
        withTrace(event.meta().traceId(), () ->
                idempotency.runOnce(event.meta().eventId(), DomainEvents.ORDER_STATUS_CHANGED, () -> {
                    var wording = describe(event.newStatus());
                    if (wording == null) {
                        // Not every transition is worth interrupting someone for.
                        // A notification per internal state change trains people
                        // to ignore notifications.
                        log.debug("No customer-facing message for status {}", event.newStatus());
                        return;
                    }
                    notificationService.record(event.customerId(), wording.type(),
                            wording.title(), wording.message(), event.orderId());
                }));
    }

    private record Wording(Notification.Type type, String title, String message) {
    }

    private Wording describe(String status) {
        return switch (status) {
            case "PAID" -> new Wording(Notification.Type.ORDER_PAID,
                    "Payment received",
                    "Thank you. Your payment went through and the kitchen has your order.");
            case "PREPARING" -> new Wording(Notification.Type.ORDER_PREPARING,
                    "Your order is being prepared",
                    "The kitchen has started on your order.");
            case "READY" -> new Wording(Notification.Type.ORDER_READY,
                    "Your order is ready",
                    "Your order is ready for collection.");
            case "DELIVERED" -> new Wording(Notification.Type.ORDER_DELIVERED,
                    "Order complete",
                    "Your order has been handed over. Enjoy your meal.");
            case "CANCELLED" -> new Wording(Notification.Type.ORDER_CANCELLED,
                    "Order cancelled",
                    "Your order has been cancelled. If you were charged, "
                            + "the payment will be reversed.");
            // PLACED produces nothing: the customer has just pressed the button
            // and is looking at the confirmation screen.
            default -> null;
        };
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        return fullName.trim().split("\\s+")[0];
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
