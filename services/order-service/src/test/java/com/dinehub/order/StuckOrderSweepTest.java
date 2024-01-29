package com.dinehub.order;

import com.dinehub.order.entity.Order;
import com.dinehub.order.entity.OrderStatus;
import com.dinehub.order.repository.OrderRepository;
import com.dinehub.order.service.StuckOrderSweep;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The sweep that makes a lost event visible.
 *
 * <p>Worth testing carefully because the failure it detects produces no error
 * anywhere: no exception, no dead-lettered message, just an order that stops
 * moving and a customer who waits.
 */
@ExtendWith(MockitoExtension.class)
class StuckOrderSweepTest {

    @Mock
    private OrderRepository orders;

    private MeterRegistry meterRegistry;
    private StuckOrderSweep sweep;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        sweep = new StuckOrderSweep(orders, meterRegistry,
                Duration.ofMinutes(10), Duration.ofMinutes(90));
    }

    private Order order() {
        return new Order(UUID.randomUUID(), "c@dinehub.local", null, null);
    }

    @Test
    @DisplayName("reports zero when nothing is stuck")
    void reportsZeroWhenHealthy() {
        when(orders.findStuckIn(any(), any())).thenReturn(List.of());

        sweep.sweep();

        assertThat(gauge("PLACED")).isZero();
        assertThat(gauge("PREPARING")).isZero();
    }

    @Test
    @DisplayName("counts orders that never moved on from PLACED")
    void countsOrdersAwaitingPayment() {
        // This is what a lost payment.completed looks like from the outside.
        when(orders.findStuckIn(eq(OrderStatus.PLACED), any()))
                .thenReturn(List.of(order(), order(), order()));
        when(orders.findStuckIn(eq(OrderStatus.PREPARING), any())).thenReturn(List.of());

        sweep.sweep();

        assertThat(gauge("PLACED")).isEqualTo(3.0);
    }

    @Test
    @DisplayName("counts orders that have been preparing too long")
    void countsOrdersStuckPreparing() {
        when(orders.findStuckIn(eq(OrderStatus.PLACED), any())).thenReturn(List.of());
        when(orders.findStuckIn(eq(OrderStatus.PREPARING), any()))
                .thenReturn(List.of(order()));

        sweep.sweep();

        assertThat(gauge("PREPARING")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the gauge falls back to zero once the orders move on")
    void gaugeRecoversWhenResolved() {
        // A gauge that only ever goes up would keep an alert firing after the
        // problem was fixed, which is how people learn to ignore alerts.
        when(orders.findStuckIn(eq(OrderStatus.PLACED), any()))
                .thenReturn(List.of(order(), order()))
                .thenReturn(List.of());
        when(orders.findStuckIn(eq(OrderStatus.PREPARING), any())).thenReturn(List.of());

        sweep.sweep();
        assertThat(gauge("PLACED")).isEqualTo(2.0);

        sweep.sweep();
        assertThat(gauge("PLACED")).isZero();
    }

    @Test
    @DisplayName("the two thresholds are applied independently")
    void appliesSeparateThresholds() {
        // An order awaiting payment for ten minutes is suspicious; an order
        // preparing for ten minutes is just lunch. One threshold for both would
        // be either too noisy or useless.
        Instant now = Instant.now();
        when(orders.findStuckIn(eq(OrderStatus.PLACED), any())).thenReturn(List.of());
        when(orders.findStuckIn(eq(OrderStatus.PREPARING), any())).thenReturn(List.of());

        sweep.sweep();

        org.mockito.ArgumentCaptor<Instant> placedBefore =
                org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.ArgumentCaptor<Instant> preparingBefore =
                org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(orders)
                .findStuckIn(eq(OrderStatus.PLACED), placedBefore.capture());
        org.mockito.Mockito.verify(orders)
                .findStuckIn(eq(OrderStatus.PREPARING), preparingBefore.capture());

        // The PREPARING cutoff reaches further back than the PLACED one.
        assertThat(preparingBefore.getValue()).isBefore(placedBefore.getValue());
        assertThat(placedBefore.getValue()).isBefore(now);
    }

    private double gauge(String status) {
        return meterRegistry.get("dinehub.orders.stuck").tag("status", status).gauge().value();
    }
}
