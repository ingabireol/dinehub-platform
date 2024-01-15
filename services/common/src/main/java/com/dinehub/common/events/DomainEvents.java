package com.dinehub.common.events;

/**
 * The event vocabulary: exchange, routing keys and queue names.
 *
 * <p>These are a contract between services, so they live in one place where a
 * change is visible to everyone rather than being string literals scattered
 * across seven codebases.
 *
 * <p>Routing keys are hierarchical ({@code order.placed}, {@code order.cancelled})
 * so a consumer can bind to {@code order.*} and receive the family. That is the
 * reason for a topic exchange rather than a direct one.
 */
public final class DomainEvents {

    private DomainEvents() {
    }

    /** One topic exchange for the whole platform. */
    public static final String EXCHANGE = "dinehub.events";

    /**
     * Dead letter exchange. Every queue routes here after its retry budget is
     * exhausted, so a poison message parks somewhere visible instead of either
     * being lost or looping forever.
     */
    public static final String DLX = "dinehub.events.dlx";
    public static final String DLQ = "dinehub.dlq";

    // --- Routing keys --------------------------------------------------------
    public static final String USER_REGISTERED = "user.registered";
    public static final String MENU_ITEM_UPDATED = "menu.item.updated";
    public static final String ORDER_PLACED = "order.placed";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String ORDER_STATUS_CHANGED = "order.status.changed";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String KITCHEN_STATUS_UPDATED = "kitchen.status.updated";

    // --- Queues --------------------------------------------------------------
    // Named <service>.<what-it-listens-for> so that an unexpected backlog in the
    // RabbitMQ console names its own owner.
    public static final String Q_PAYMENT_ORDER_PLACED = "payment.order-placed";
    public static final String Q_ORDER_PAYMENT = "order.payment-result";
    public static final String Q_ORDER_KITCHEN = "order.kitchen-status";
    public static final String Q_KITCHEN_PAYMENT = "kitchen.payment-completed";
    public static final String Q_KITCHEN_ORDER_CANCELLED = "kitchen.order-cancelled";
    public static final String Q_NOTIFICATION_ORDER_STATUS = "notification.order-status";
    public static final String Q_NOTIFICATION_USER_REGISTERED = "notification.user-registered";
}
