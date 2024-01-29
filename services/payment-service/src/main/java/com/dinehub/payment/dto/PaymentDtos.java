package com.dinehub.payment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record PaymentResponse(
            UUID id,
            UUID orderId,
            UUID customerId,
            BigDecimal amount,
            String status,
            String reference,
            String failureReason,
            Instant createdAt
    ) {
    }
}
