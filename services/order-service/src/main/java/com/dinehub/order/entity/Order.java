package com.dinehub.order.entity;

import com.dinehub.common.api.ApiExceptions;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "customer_email", nullable = false, length = 160)
    private String customerEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "delivery_address", length = 400)
    private String deliveryAddress;

    @Column(length = 500)
    private String notes;

    @Column(name = "cancellation_reason", length = 300)
    private String cancellationReason;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OrderItem> items = new ArrayList<>();

    /**
     * Optimistic locking. Two events — a payment result and a cancellation —
     * can arrive concurrently; without this one silently overwrites the other.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected Order() {
    }

    public Order(UUID customerId, String customerEmail, String deliveryAddress, String notes) {
        this.id = UUID.randomUUID();
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.deliveryAddress = deliveryAddress;
        this.notes = notes;
        this.status = OrderStatus.PLACED;
        this.totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        this.placedAt = Instant.now();
        this.updatedAt = this.placedAt;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.attachTo(this);
        recalculateTotal();
    }

    /**
     * Sums the line totals.
     *
     * <p>Each line is quantity × the price snapshotted when the order was
     * placed — never the live menu price. A price change afterwards must not
     * retroactively alter what the customer was charged.
     */
    private void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        this.updatedAt = Instant.now();
    }

    /**
     * Moves the order to a new status, refusing illegal transitions.
     *
     * @throws ApiExceptions.ConflictException if the transition is not allowed
     */
    public void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new ApiExceptions.ConflictException(
                    "Cannot move an order from %s to %s".formatted(status, next));
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    /**
     * Moves to a new status if the transition is legal, and reports whether it
     * happened.
     *
     * <p>Used by event consumers: a redelivered or out-of-order event should be
     * ignored, not turned into an exception that dead-letters a message which
     * was in fact already handled.
     */
    public boolean tryTransitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            return false;
        }
        this.status = next;
        this.updatedAt = Instant.now();
        return true;
    }

    public void cancel(String reason) {
        if (!status.isCancellable()) {
            throw new ApiExceptions.ConflictException(
                    "An order that is %s can no longer be cancelled".formatted(status));
        }
        this.status = OrderStatus.CANCELLED;
        this.cancellationReason = reason;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getDeliveryAddress() {
        return deliveryAddress;
    }

    public String getNotes() {
        return notes;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public long getVersion() {
        return version;
    }
}
