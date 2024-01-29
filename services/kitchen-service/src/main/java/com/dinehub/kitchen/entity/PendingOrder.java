package com.dinehub.kitchen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * What an order contains, remembered from {@code order.placed} so that the
 * ticket created at {@code payment.completed} can be legible.
 *
 * <p>The kitchen needs two different facts from two different events: *what* to
 * cook (known when the order is placed) and *whether* to cook it (known when the
 * payment clears). Rather than calling order-service back — which would put a
 * synchronous dependency on the dinner-rush path and blank the board whenever
 * order-service restarts — kitchen-service keeps this small cache.
 *
 * <p>Rows are pruned once the ticket exists; see {@code PendingOrderCleanup}.
 */
@Entity
@Table(name = "pending_orders")
public class PendingOrder {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "items_summary", nullable = false, length = 1000)
    private String itemsSummary;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected PendingOrder() {
    }

    public PendingOrder(UUID orderId, UUID customerId, String itemsSummary, int itemCount) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.itemsSummary = itemsSummary;
        this.itemCount = itemCount;
        this.recordedAt = Instant.now();
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

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
