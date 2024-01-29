package com.dinehub.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A simulated payment provider.
 *
 * <p>There is no real provider here, and pretending otherwise would be the wrong
 * shape for a portfolio project. What this does instead is make the failure path
 * <em>reachable on demand</em>, because the interesting operational behaviour —
 * an order cancelled by a declined card, a dead-letter queue filling, an alert
 * firing — only exists if payments can fail.
 *
 * <p>The failure rate is configurable per environment: 10% in dev and test so
 * the path is exercised constantly, 0% in prod so a demo does not randomly fail.
 */
@Component
public class PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(PaymentGateway.class);

    /** Reasons a real provider would give. */
    private static final List<String> DECLINE_REASONS = List.of(
            "Card declined by issuer",
            "Insufficient funds",
            "Card expired",
            "Transaction flagged by fraud rules");

    private final double failureRate;
    private final long simulatedLatencyMs;

    public PaymentGateway(
            @Value("${dinehub.payment.failure-rate:0.1}") double failureRate,
            @Value("${dinehub.payment.simulated-latency-ms:200}") long simulatedLatencyMs) {

        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException(
                    "dinehub.payment.failure-rate must be between 0.0 and 1.0, got " + failureRate);
        }
        this.failureRate = failureRate;
        this.simulatedLatencyMs = simulatedLatencyMs;
        log.info("Payment gateway simulation: {}% failure rate, ~{}ms latency",
                failureRate * 100, simulatedLatencyMs);
    }

    public record Result(boolean successful, String failureReason) {

        public static Result success() {
            return new Result(true, null);
        }

        public static Result declined(String reason) {
            return new Result(false, reason);
        }
    }

    /**
     * "Charges" the card.
     *
     * <p>The simulated latency is not decoration: a payment call that returns
     * instantly hides the fact that the order sits in PLACED while it is in
     * flight, which is exactly the window the stuck-order sweep watches.
     */
    public Result charge(UUID orderId, BigDecimal amount) {
        if (simulatedLatencyMs > 0) {
            try {
                Thread.sleep(ThreadLocalRandom.current()
                        .nextLong(simulatedLatencyMs / 2, simulatedLatencyMs * 2 + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.declined("Payment interrupted");
            }
        }

        // A deterministic hook for tests and demos: an amount ending in .13
        // always declines, so the failure path can be shown on cue rather than
        // waited for.
        if (amount.remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.13")) == 0) {
            log.info("Order {} declined by the reserved test amount rule", orderId);
            return Result.declined("Card declined by issuer");
        }

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            String reason = DECLINE_REASONS.get(
                    ThreadLocalRandom.current().nextInt(DECLINE_REASONS.size()));
            log.info("Order {} declined: {}", orderId, reason);
            return Result.declined(reason);
        }

        return Result.success();
    }
}
