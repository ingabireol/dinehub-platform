package com.dinehub.order;

import com.dinehub.order.entity.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lifecycle rules, tested exhaustively.
 *
 * <p>Worth the thoroughness: these transitions are driven by events that arrive
 * out of order under redelivery, so "can a late payment.completed resurrect a
 * cancelled order?" is a question with a real answer and a real consequence.
 */
class OrderStatusTest {

    @ParameterizedTest(name = "{0} → {1} is allowed")
    @CsvSource({
            "PLACED, PAID",
            "PLACED, CANCELLED",
            "PAID, PREPARING",
            "PAID, CANCELLED",
            "PREPARING, READY",
            "READY, DELIVERED",
    })
    @DisplayName("the legal transitions are permitted")
    void allowsLegalTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} → {1} is refused")
    @CsvSource({
            // Skipping payment
            "PLACED, PREPARING",
            "PLACED, READY",
            "PLACED, DELIVERED",
            // Skipping the kitchen
            "PAID, READY",
            "PAID, DELIVERED",
            // Going backwards
            "PAID, PLACED",
            "PREPARING, PAID",
            "READY, PREPARING",
            "DELIVERED, READY",
            // Cancelling after the food exists
            "PREPARING, CANCELLED",
            "READY, CANCELLED",
            "DELIVERED, CANCELLED",
    })
    @DisplayName("illegal transitions are refused")
    void refusesIllegalTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("no status may transition to itself")
    void refusesSelfTransition(OrderStatus status) {
        // A redelivered event will try exactly this. Allowing it would emit a
        // duplicate order.status.changed and a duplicate notification.
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DELIVERED", "CANCELLED"})
    @DisplayName("a terminal status cannot move anywhere")
    void terminalStatusesAreFinal(OrderStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        for (OrderStatus other : OrderStatus.values()) {
            assertThat(terminal.canTransitionTo(other))
                    .as("%s should not move to %s", terminal, other)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a late payment.completed cannot resurrect a cancelled order")
    void cancelledOrderStaysCancelled() {
        // The concrete scenario: the customer cancels, and a payment event that
        // was already in flight is delivered afterwards. Without this rule the
        // order silently becomes PAID and the kitchen starts cooking it.
        assertThat(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.PAID)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PLACED", "PAID"})
    @DisplayName("an order is cancellable until the kitchen starts")
    void cancellableBeforePreparation(OrderStatus status) {
        assertThat(status.isCancellable()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class,
            names = {"PREPARING", "READY", "DELIVERED", "CANCELLED"})
    @DisplayName("an order is not cancellable once the kitchen has started")
    void notCancellableAfterPreparation(OrderStatus status) {
        // Once the food exists somebody is paying for it. Cancellation after
        // this point is a refund conversation, not a status change.
        assertThat(status.isCancellable()).isFalse();
    }

    @Test
    @DisplayName("every non-terminal status has somewhere to go")
    void noDeadEnds() {
        // A status with no legal next step and no terminal flag would strand
        // orders there with nothing reporting it.
        for (OrderStatus status : OrderStatus.values()) {
            if (status.isTerminal()) {
                continue;
            }
            boolean hasSomewhereToGo = false;
            for (OrderStatus next : OrderStatus.values()) {
                if (status.canTransitionTo(next)) {
                    hasSomewhereToGo = true;
                    break;
                }
            }
            assertThat(hasSomewhereToGo)
                    .as("%s is non-terminal but has no legal next status", status)
                    .isTrue();
        }
    }
}
