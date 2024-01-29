package com.dinehub.order;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.order.entity.Order;
import com.dinehub.order.entity.OrderItem;
import com.dinehub.order.entity.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderEntityTest {

    private Order newOrder() {
        return new Order(UUID.randomUUID(), "customer@dinehub.local", "12 Example Street", null);
    }

    @Test
    @DisplayName("a new order starts PLACED with a zero total")
    void startsPlaced() {
        var order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("0.00");
        assertThat(order.getItems()).isEmpty();
        assertThat(order.getId()).isNotNull();
    }

    @Test
    @DisplayName("the total is the sum of the line totals")
    void sumsLineTotals() {
        var order = newOrder();
        order.addItem(new OrderItem(UUID.randomUUID(), "Tilapia", new BigDecimal("12.50"), 2));
        order.addItem(new OrderItem(UUID.randomUUID(), "Juice", new BigDecimal("3.00"), 3));

        // 2 × 12.50 + 3 × 3.00 = 34.00
        assertThat(order.getTotalAmount()).isEqualByComparingTo("34.00");
        assertThat(order.getTotalAmount().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("a line total keeps two decimal places")
    void lineTotalScale() {
        var line = new OrderItem(UUID.randomUUID(), "Tea", new BigDecimal("2.005"), 3);

        // The unit price rounds to 2.01, so the line is 6.03 — not 6.015.
        assertThat(line.getUnitPrice()).isEqualByComparingTo("2.01");
        assertThat(line.lineTotal()).isEqualByComparingTo("6.03");
        assertThat(line.lineTotal().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("the item list cannot be modified from outside")
    void itemsAreNotExposedForMutation() {
        // Adding a line without going through addItem would leave the total
        // stale, and the receipt would not match what was charged.
        var order = newOrder();
        order.addItem(new OrderItem(UUID.randomUUID(), "Tea", new BigDecimal("2.00"), 1));

        assertThatThrownBy(() -> order.getItems().add(
                new OrderItem(UUID.randomUUID(), "Sneaky", new BigDecimal("0.01"), 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a legal transition moves the status and the timestamp")
    void transitionsLegally() throws InterruptedException {
        var order = newOrder();
        var before = order.getUpdatedAt();
        Thread.sleep(2);

        order.transitionTo(OrderStatus.PAID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getUpdatedAt()).isAfter(before);
    }

    @Test
    @DisplayName("an illegal transition is a 409, not a silent no-op")
    void refusesIllegalTransition() {
        var order = newOrder();

        assertThatThrownBy(() -> order.transitionTo(OrderStatus.DELIVERED))
                .isInstanceOf(ApiExceptions.ConflictException.class)
                .hasMessageContaining("PLACED")
                .hasMessageContaining("DELIVERED");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
    }

    @Test
    @DisplayName("tryTransitionTo reports failure instead of throwing")
    void tryTransitionReportsRatherThanThrows() {
        // Event consumers use this form. A late or out-of-order event should be
        // ignored, not dead-lettered — nothing is actually wrong.
        var order = newOrder();

        assertThat(order.tryTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(order.tryTransitionTo(OrderStatus.PAID)).isFalse();
        assertThat(order.tryTransitionTo(OrderStatus.DELIVERED)).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("an order can be cancelled before the kitchen starts")
    void cancelsBeforePreparation() {
        var order = newOrder();

        order.cancel("Changed my mind");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancellationReason()).isEqualTo("Changed my mind");
    }

    @Test
    @DisplayName("an order cannot be cancelled once the kitchen has started")
    void refusesLateCancellation() {
        var order = newOrder();
        order.transitionTo(OrderStatus.PAID);
        order.transitionTo(OrderStatus.PREPARING);

        assertThatThrownBy(() -> order.cancel("too late"))
                .isInstanceOf(ApiExceptions.ConflictException.class)
                .hasMessageContaining("PREPARING");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PREPARING);
    }

    @Test
    @DisplayName("a line snapshots the name and price it was created with")
    void linesAreSnapshots() {
        // The whole point: the menu can change afterwards and this order still
        // records what the customer actually agreed to pay.
        UUID menuItemId = UUID.randomUUID();
        var line = new OrderItem(menuItemId, "Grilled Tilapia", new BigDecimal("12.50"), 1);

        assertThat(line.getMenuItemId()).isEqualTo(menuItemId);
        assertThat(line.getItemName()).isEqualTo("Grilled Tilapia");
        assertThat(line.getUnitPrice()).isEqualByComparingTo("12.50");
    }
}
