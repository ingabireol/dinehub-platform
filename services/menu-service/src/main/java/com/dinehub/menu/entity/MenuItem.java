package com.dinehub.menu.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "menu_items")
public class MenuItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 140)
    private String name;

    @Column(length = 600)
    private String description;

    /**
     * Money is BigDecimal with an explicit scale, never double. A double cannot
     * represent 0.10 exactly, and the rounding error compounds across an order.
     */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean available;

    @Column(name = "preparation_minutes", nullable = false)
    private int preparationMinutes;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic locking. Two admins editing the same item at once would
     * otherwise silently overwrite each other; the second save now fails and the
     * client is told to re-read.
     */
    @Version
    @Column(nullable = false)
    private long version;

    protected MenuItem() {
    }

    public MenuItem(Category category, String name, String description, BigDecimal price,
                    int preparationMinutes, String imageUrl) {
        this.id = UUID.randomUUID();
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price.setScale(2, java.math.RoundingMode.HALF_UP);
        this.preparationMinutes = preparationMinutes;
        this.imageUrl = imageUrl;
        this.available = true;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Category getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isAvailable() {
        return available;
    }

    public int getPreparationMinutes() {
        return preparationMinutes;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void update(Category category, String name, String description, BigDecimal price,
                       int preparationMinutes, String imageUrl) {
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price.setScale(2, java.math.RoundingMode.HALF_UP);
        this.preparationMinutes = preparationMinutes;
        this.imageUrl = imageUrl;
        this.updatedAt = Instant.now();
    }

    public void setAvailable(boolean available) {
        this.available = available;
        this.updatedAt = Instant.now();
    }
}
