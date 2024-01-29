package com.dinehub.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Event payloads.
 *
 * <p>Every event carries an {@code eventId}. That single field is what makes
 * consumers idempotent: RabbitMQ guarantees at-least-once delivery, so a
 * consumer <em>will</em> see the same message twice eventually — usually during
 * a redeploy, which is the worst time to discover you charged someone twice.
 *
 * <p>Events are records, so they are immutable and their JSON shape is obvious
 * from the declaration.
 */
public final class EventPayloads {

    private EventPayloads() {
    }

    /** Fields every event carries. Composed rather than inherited: records cannot extend. */
    public record EventMeta(UUID eventId, Instant occurredAt, String traceId) {
        public static EventMeta now(String traceId) {
            return new EventMeta(UUID.randomUUID(), Instant.now(), traceId);
        }
    }

    public record UserRegistered(
            EventMeta meta,
            UUID userId,
            String email,
            String fullName,
            String role
    ) {
    }

    public record MenuItemUpdated(
            EventMeta meta,
            UUID itemId,
            String name,
            BigDecimal price,
            boolean available
    ) {
    }

    public record OrderPlaced(
            EventMeta meta,
            UUID orderId,
            UUID customerId,
            String customerEmail,
            BigDecimal totalAmount,
            int itemCount,

            /*
             * A plain-text summary of the order lines, e.g. "2 × Rwandan Tea,
             * 1 × Grilled Tilapia".
             *
             * Carried on the event so kitchen-service can render a legible
             * ticket without calling order-service. The kitchen board has to
             * render during a dinner rush, and a synchronous call per ticket is
             * exactly the wrong thing to add to that path — it would also mean
             * the board goes blank whenever order-service restarts.
             */
            String itemsSummary
    ) {
    }

    public record OrderCancelled(
            EventMeta meta,
            UUID orderId,
            UUID customerId,
            String reason
    ) {
    }

    public record OrderStatusChanged(
            EventMeta meta,
            UUID orderId,
            UUID customerId,
            String previousStatus,
            String newStatus
    ) {
    }

    public record PaymentCompleted(
            EventMeta meta,
            UUID paymentId,
            UUID orderId,
            UUID customerId,
            BigDecimal amount,
            String reference
    ) {
    }

    public record PaymentFailed(
            EventMeta meta,
            UUID paymentId,
            UUID orderId,
            UUID customerId,
            BigDecimal amount,
            String reason
    ) {
    }

    public record KitchenStatusUpdated(
            EventMeta meta,
            UUID orderId,
            String status,
            UUID updatedBy
    ) {
    }
}
