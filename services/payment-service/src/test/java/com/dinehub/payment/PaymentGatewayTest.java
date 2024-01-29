package com.dinehub.payment;

import com.dinehub.payment.service.PaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentGatewayTest {

    @Test
    @DisplayName("a zero failure rate always succeeds")
    void alwaysSucceedsAtZeroFailureRate() {
        var gateway = new PaymentGateway(0.0, 0);

        for (int i = 0; i < 50; i++) {
            assertThat(gateway.charge(UUID.randomUUID(), new BigDecimal("10.00")).successful())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a failure rate of one always declines, with a reason")
    void alwaysFailsAtFullFailureRate() {
        var gateway = new PaymentGateway(1.0, 0);

        var result = gateway.charge(UUID.randomUUID(), new BigDecimal("10.00"));

        assertThat(result.successful()).isFalse();
        assertThat(result.failureReason()).isNotBlank();
    }

    @Test
    @DisplayName("an amount ending in .13 always declines, whatever the rate")
    void reservedTestAmountAlwaysDeclines() {
        // A deterministic hook, so the failure path can be demonstrated on cue
        // rather than waited for.
        var gateway = new PaymentGateway(0.0, 0);

        var result = gateway.charge(UUID.randomUUID(), new BigDecimal("28.13"));

        assertThat(result.successful()).isFalse();
        assertThat(result.failureReason()).isEqualTo("Card declined by issuer");
    }

    @Test
    @DisplayName("an out-of-range failure rate is refused at startup")
    void rejectsInvalidFailureRate() {
        // Misconfiguring this to 10 instead of 0.10 would decline every payment
        // in production. Failing at startup is much better than discovering it
        // from customer complaints.
        assertThatThrownBy(() -> new PaymentGateway(1.5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0.0 and 1.0");

        assertThatThrownBy(() -> new PaymentGateway(-0.1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a declined result always carries a reason, a successful one never does")
    void resultShapeIsConsistent() {
        // The database CHECK constraint enforces the same rule, so a mismatch
        // here would fail the insert rather than store a nonsense row.
        assertThat(PaymentGateway.Result.success().failureReason()).isNull();
        assertThat(PaymentGateway.Result.declined("Card expired").failureReason())
                .isEqualTo("Card expired");
    }
}
