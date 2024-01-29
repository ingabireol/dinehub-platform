package com.dinehub.payment;

import com.dinehub.payment.entity.Payment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentEntityTest {

    @Test
    @DisplayName("a completed payment has no failure reason")
    void completedHasNoReason() {
        var payment = Payment.completed(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("28.00"));

        assertThat(payment.isSuccessful()).isTrue();
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.COMPLETED);
        assertThat(payment.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("a failed payment carries its reason")
    void failedCarriesReason() {
        var payment = Payment.failed(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("28.00"), "Insufficient funds");

        assertThat(payment.isSuccessful()).isFalse();
        assertThat(payment.getFailureReason()).isEqualTo("Insufficient funds");
    }

    @Test
    @DisplayName("the amount is stored at two decimal places")
    void normalisesAmount() {
        var payment = Payment.completed(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("28.005"));

        assertThat(payment.getAmount().scale()).isEqualTo(2);
        assertThat(payment.getAmount()).isEqualByComparingTo("28.01");
    }

    @Test
    @DisplayName("references are unique and not sequential")
    void referencesAreUniqueAndOpaque() {
        // A sequential reference tells whoever holds one roughly how many
        // payments the platform has taken, and lets them guess their neighbours'.
        Set<String> references = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            references.add(Payment.completed(UUID.randomUUID(), UUID.randomUUID(),
                    new BigDecimal("10.00")).getReference());
        }

        assertThat(references).hasSize(500);
        assertThat(references).allMatch(r -> r.startsWith("PAY-") && r.length() == 16);
    }
}
