package com.dinehub.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    public enum Type {
        WELCOME,
        ORDER_PLACED,
        ORDER_PAID,
        ORDER_PREPARING,
        ORDER_READY,
        ORDER_DELIVERED,
        ORDER_CANCELLED,
        PAYMENT_FAILED
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Type type;

    @Column(nullable = false, length = 140)
    private String title;

    @Column(nullable = false, length = 600)
    private String message;

    /** The order this is about, where there is one. Lets the UI deep-link. */
    @Column(name = "related_order_id")
    private UUID relatedOrderId;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(UUID userId, Type type, String title,
                        String message, UUID relatedOrderId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.relatedOrderId = relatedOrderId;
        this.createdAt = Instant.now();
    }

    /**
     * Marks it read.
     *
     * <p>Idempotent on purpose: the client marks notifications read as they
     * scroll, which sends the same request more than once, and the first read
     * time is the one worth keeping.
     */
    public void markRead() {
        if (readAt == null) {
            this.readAt = Instant.now();
        }
    }

    public boolean isRead() {
        return readAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Type getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public UUID getRelatedOrderId() {
        return relatedOrderId;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
