package com.dinehub.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One line on an order.
 *
 * <p>The item name and unit price are <b>copied</b> from the menu at the moment
 * the order is placed, not referenced. This is the single most important
 * modelling decision in the service: a later price change or a renamed dish must
 * not alter what a historic order says the customer was charged.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** The menu item this came from. A reference for reporting, not for pricing. */
    @Column(name = "menu_item_id", nullable = false)
    private UUID menuItemId;

    /** Snapshotted. The menu item may since have been renamed or deleted. */
    @Column(name = "item_name", nullable = false, length = 140)
    private String itemName;

    /** Snapshotted. This is what the customer agreed to pay. */
    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private int quantity;

    protected OrderItem() {
    }

    public OrderItem(UUID menuItemId, String itemName, BigDecimal unitPrice, int quantity) {
        this.id = UUID.randomUUID();
        this.menuItemId = menuItemId;
        this.itemName = itemName;
        this.unitPrice = unitPrice.setScale(2, RoundingMode.HALF_UP);
        this.quantity = quantity;
    }

    void attachTo(Order order) {
        this.order = order;
    }

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMenuItemId() {
        return menuItemId;
    }

    public String getItemName() {
        return itemName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }
}
