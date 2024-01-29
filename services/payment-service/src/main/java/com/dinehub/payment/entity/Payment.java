package com.dinehub.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    public enum Status {
        COMPLETED, FAILED
    }

    @Id
    private UUID id;

    /**
     * Unique. Two payments for one order is the failure this service exists to
     * prevent, and the database constraint is what actually prevents it — an
     * application-level check has a race in it.
     */
    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** What a customer would quote when querying a charge. */
    @Column(nullable = false, unique = true, length = 32)
    private String reference;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
    }

    private Payment(UUID orderId, UUID customerId, BigDecimal amount,
                    Status status, String failureReason) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.status = status;
        this.failureReason = failureReason;
        this.createdAt = Instant.now();
        this.reference = generateReference();
    }

    public static Payment completed(UUID orderId, UUID customerId, BigDecimal amount) {
        return new Payment(orderId, customerId, amount, Status.COMPLETED, null);
    }

    public static Payment failed(UUID orderId, UUID customerId,
                                 BigDecimal amount, String reason) {
        return new Payment(orderId, customerId, amount, Status.FAILED, reason);
    }

    /**
     * A short, human-quotable reference.
     *
     * <p>Derived from a random UUID rather than a sequence: a sequential
     * reference tells anyone holding one roughly how many payments the platform
     * has taken, and lets them guess their neighbours'.
     */
    private static String generateReference() {
        return "PAY-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 12).toUpperCase();
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

    public BigDecimal getAmount() {
        return amount;
    }

    public Status getStatus() {
        return status;
    }

    public String getReference() {
        return reference;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isSuccessful() {
        return status == Status.COMPLETED;
    }
}
