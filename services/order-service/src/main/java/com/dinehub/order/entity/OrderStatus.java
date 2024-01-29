package com.dinehub.order.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * The order lifecycle, with the legal transitions encoded.
 *
 * <p>Putting the transition rules on the enum rather than scattering
 * {@code if (status == ...)} checks through the service means there is exactly
 * one place that knows what is allowed, and it cannot disagree with itself.
 */
public enum OrderStatus {

    /** Created, not yet paid. */
    PLACED,

    /** Payment succeeded. The kitchen can now see it. */
    PAID,

    /** The kitchen has started. No longer cancellable. */
    PREPARING,

    /** Ready for collection or delivery. */
    READY,

    /** Complete. Terminal. */
    DELIVERED,

    /** Cancelled before preparation began, or payment failed. Terminal. */
    CANCELLED;

    private static final Set<OrderStatus> TERMINAL = EnumSet.of(DELIVERED, CANCELLED);

    /** Statuses from which a customer may still cancel. */
    private static final Set<OrderStatus> CANCELLABLE = EnumSet.of(PLACED, PAID);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isCancellable() {
        // Once the kitchen has started cooking, the food exists and somebody is
        // paying for it. Cancellation after PREPARING is a refund conversation,
        // not a status change.
        return CANCELLABLE.contains(this);
    }

    /**
     * Whether this status may move to {@code next}.
     *
     * <p>Rejecting an illegal transition matters because the events that drive
     * them arrive out of order under redelivery: a late {@code payment.completed}
     * must not move a CANCELLED order back to PAID.
     */
    public boolean canTransitionTo(OrderStatus next) {
        if (this == next) {
            return false;
        }
        return switch (this) {
            case PLACED -> next == PAID || next == CANCELLED;
            case PAID -> next == PREPARING || next == CANCELLED;
            case PREPARING -> next == READY;
            case READY -> next == DELIVERED;
            case DELIVERED, CANCELLED -> false;
        };
    }
}
