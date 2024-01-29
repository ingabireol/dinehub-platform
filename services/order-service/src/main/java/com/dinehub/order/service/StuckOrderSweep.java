package com.dinehub.order.service;

import com.dinehub.order.entity.OrderStatus;
import com.dinehub.order.repository.OrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds orders that have stopped moving.
 *
 * <p>Events can be lost entirely — a broker restart at the wrong moment, a
 * consumer that crashed between acknowledging and committing. When that happens
 * an order sits in PLACED forever and <em>nothing reports it</em>: no error is
 * thrown, no message is dead-lettered, and the customer is simply waiting.
 *
 * <p>This is the sweep that makes that visible. It does not try to repair
 * anything — automatically re-driving a payment would be worse than the problem.
 * It exports a gauge, and the alert rule on that gauge is what gets a human to
 * look. See observability/prometheus/alerts/.
 */
@Component
public class StuckOrderSweep {

    private static final Logger log = LoggerFactory.getLogger(StuckOrderSweep.class);

    private final OrderRepository orders;
    private final Duration awaitingPaymentThreshold;
    private final Duration preparingThreshold;

    private final AtomicInteger stuckAwaitingPayment = new AtomicInteger();
    private final AtomicInteger stuckPreparing = new AtomicInteger();

    public StuckOrderSweep(
            OrderRepository orders,
            MeterRegistry meterRegistry,
            @Value("${dinehub.sweep.awaiting-payment-threshold:10m}") Duration awaitingPayment,
            @Value("${dinehub.sweep.preparing-threshold:90m}") Duration preparing) {

        this.orders = orders;
        this.awaitingPaymentThreshold = awaitingPayment;
        this.preparingThreshold = preparing;

        Gauge.builder("dinehub.orders.stuck", stuckAwaitingPayment, AtomicInteger::get)
                .description("Orders that have not moved on from PLACED within the threshold")
                .tag("status", "PLACED")
                .register(meterRegistry);

        Gauge.builder("dinehub.orders.stuck", stuckPreparing, AtomicInteger::get)
                .description("Orders that have been PREPARING longer than the threshold")
                .tag("status", "PREPARING")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${dinehub.sweep.interval:PT2M}")
    @Transactional(readOnly = true)
    public void sweep() {
        Instant now = Instant.now();

        var awaitingPayment = orders.findStuckIn(
                OrderStatus.PLACED, now.minus(awaitingPaymentThreshold));
        stuckAwaitingPayment.set(awaitingPayment.size());

        var preparing = orders.findStuckIn(
                OrderStatus.PREPARING, now.minus(preparingThreshold));
        stuckPreparing.set(preparing.size());

        if (!awaitingPayment.isEmpty()) {
            log.warn("{} order(s) still awaiting payment after {}: {}",
                    awaitingPayment.size(), awaitingPaymentThreshold,
                    awaitingPayment.stream().map(o -> o.getId().toString()).limit(10).toList());
        }
        if (!preparing.isEmpty()) {
            log.warn("{} order(s) preparing for longer than {}: {}",
                    preparing.size(), preparingThreshold,
                    preparing.stream().map(o -> o.getId().toString()).limit(10).toList());
        }
    }
}
