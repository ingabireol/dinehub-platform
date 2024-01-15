package com.dinehub.menu;

import com.dinehub.menu.entity.Category;
import com.dinehub.menu.entity.MenuItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class MenuEntityTest {

    private final Category mains = new Category("Mains", "Main courses", 2);

    @Test
    @DisplayName("a price is rounded to two decimal places on construction")
    void normalisesPriceOnCreate() {
        // Money with a drifting scale produces receipts whose lines do not sum
        // to the total, which customers notice and finance has to reconcile.
        var item = new MenuItem(mains, "Odd", null, new BigDecimal("12.509"), 10, null);

        assertThat(item.getPrice().scale()).isEqualTo(2);
        assertThat(item.getPrice()).isEqualByComparingTo("12.51");
    }

    @Test
    @DisplayName("a price is rounded half-up, not truncated")
    void roundsHalfUp() {
        var item = new MenuItem(mains, "Half", null, new BigDecimal("9.995"), 10, null);

        assertThat(item.getPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("a new item is available by default")
    void newItemIsAvailable() {
        var item = new MenuItem(mains, "Fresh", null, new BigDecimal("5.00"), 10, null);

        assertThat(item.isAvailable()).isTrue();
        assertThat(item.getId()).isNotNull();
        assertThat(item.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("changing availability moves the updated timestamp")
    void availabilityChangeTouchesTimestamp() throws InterruptedException {
        var item = new MenuItem(mains, "Toggle", null, new BigDecimal("5.00"), 10, null);
        var before = item.getUpdatedAt();
        Thread.sleep(2);

        item.setAvailable(false);

        assertThat(item.isAvailable()).isFalse();
        assertThat(item.getUpdatedAt()).isAfter(before);
    }

    @Test
    @DisplayName("an update re-normalises the price and moves the category")
    void updateRenormalisesPrice() {
        var other = new Category("Specials", null, 9);
        var item = new MenuItem(mains, "Before", null, new BigDecimal("5.00"), 10, null);

        item.update(other, "After", "New description", new BigDecimal("7.1"), 15, "img.png");

        assertThat(item.getName()).isEqualTo("After");
        assertThat(item.getCategory()).isEqualTo(other);
        assertThat(item.getPrice()).isEqualByComparingTo("7.10");
        assertThat(item.getPrice().scale()).isEqualTo(2);
        assertThat(item.getPreparationMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("a category can be renamed and reordered")
    void categoryUpdate() {
        var category = new Category("Old", "Old description", 1);

        category.update("New", "New description", 5);

        assertThat(category.getName()).isEqualTo("New");
        assertThat(category.getDisplayOrder()).isEqualTo(5);
    }
}
