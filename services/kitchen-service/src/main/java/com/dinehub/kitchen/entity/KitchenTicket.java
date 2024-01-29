package com.dinehub.kitchen.entity;

import com.dinehub.common.api.ApiExceptions;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A ticket on the kitchen board.
 *
 * <p>Created when payment completes, not when the order is placed. Cooking food
 * that has not been paid for is the kind of thing that only has to happen a few
 * times to matter.
 */
@Entity
@Table(name = "kitchen_tickets")
public class KitchenTicket {

    public enum Status {
        /** Paid, waiting for someone to pick it up. */
        QUEUED,
        /** Being cooked. From here the order can no longer be cancelled. */
        PREPARING,
        /** Ready for collection or delivery. */
        READY,
        /** Handed over. Terminal. */
        DELIVERED,
        /** The order was cancelled before cooking started. Terminal. */
        CANCELLED
    }

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    /**
     * A plain-text summary of what to cook.
     *
     * <p>Denormalised on purpose. The kitchen board must render during a
     * dinner rush without a synchronous call to order-service, and a ticket
     * should still be legible if order-service is down.
     */
    @Column(name = "items_summary", nullable = false, length = 1000)
    private String itemsSummary;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "claimed_by")
    private UUID claimedBy;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected KitchenTicket() {
    }

    public KitchenTicket(UUID orderId, UUID customerId, String itemsSummary, int itemCount) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.customerId = customerId;
        this.itemsSummary = itemsSummary;
        this.itemCount = itemCount;
        this.status = Status.QUEUED;
        this.queuedAt = Instant.now();
    }

    /**
     * A chef picks the ticket up and starts cooking.
     *
     * <p>Recording who claimed it is what makes "two chefs cooked the same
     * order" visible rather than merely expensive.
     */
    public void startPreparing(UUID chefId) {
        requireStatus(Status.QUEUED, "start preparing");
        this.status = Status.PREPARING;
        this.claimedBy = chefId;
        this.startedAt = Instant.now();
    }

    public void markReady() {
        requireStatus(Status.PREPARING, "mark ready");
        this.status = Status.READY;
        this.readyAt = Instant.now();
    }

    public void markDelivered() {
        requireStatus(Status.READY, "mark delivered");
        this.status = Status.DELIVERED;
        this.completedAt = Instant.now();
    }

    /**
     * Cancels the ticket.
     *
     * @return true if it was cancelled, false if cooking had already started
     */
    public boolean cancel() {
        if (status != Status.QUEUED) {
            // Once a chef has started, the food exists. Removing the ticket
            // would leave them cooking something nobody is tracking.
            return false;
        }
        this.status = Status.CANCELLED;
        this.completedAt = Instant.now();
        return true;
    }

    private void requireStatus(Status expected, String action) {
        if (this.status != expected) {
            throw new ApiExceptions.ConflictException(
                    "Cannot %s a ticket that is %s".formatted(action, status));
        }
    }

    /** How long this ticket took to cook, once it is ready. */
    public Duration preparationTime() {
        if (startedAt == null || readyAt == null) {
            return null;
        }
        return Duration.between(startedAt, readyAt);
    }

    /** How long it has been waiting unclaimed. Drives the board's ordering. */
    public Duration waitingTime() {
        Instant end = startedAt != null ? startedAt : Instant.now();
        return Duration.between(queuedAt, end);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getItemsSummary() {
        return itemsSummary;
    }

    public int getItemCount() {
        return itemCount;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getClaimedBy() {
        return claimedBy;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getReadyAt() {
        return readyAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
