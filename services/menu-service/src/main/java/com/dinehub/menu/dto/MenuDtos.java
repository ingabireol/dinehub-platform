package com.dinehub.menu.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MenuDtos {

    private MenuDtos() {
    }

    public record CategoryRequest(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 400) String description,
            @Min(0) @Max(999) int displayOrder
    ) {
    }

    public record CategoryResponse(
            UUID id,
            String name,
            String description,
            int displayOrder,
            int itemCount
    ) {
    }

    public record MenuItemRequest(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 140) String name,
            @Size(max = 600) String description,

            // Two decimal places, and a ceiling. The maximum is not paranoia:
            // a mistyped price is the single most common admin error, and
            // 100,000 for a side of chips should be refused, not sold.
            @NotNull
            @DecimalMin(value = "0.01", message = "must be at least 0.01")
            @DecimalMax(value = "10000.00", message = "must not exceed 10000.00")
            @Digits(integer = 5, fraction = 2)
            BigDecimal price,

            @Min(1) @Max(240) int preparationMinutes,
            @Size(max = 500) String imageUrl
    ) {
    }

    public record MenuItemResponse(
            UUID id,
            UUID categoryId,
            String categoryName,
            String name,
            String description,
            BigDecimal price,
            boolean available,
            int preparationMinutes,
            String imageUrl,
            Instant updatedAt
    ) {
    }

    public record AvailabilityRequest(
            @NotNull Boolean available
    ) {
    }

    /**
     * Used by order-service to price an order.
     *
     * <p>It asks for a batch of items by id and gets back name, price and
     * availability as of now. order-service then snapshots those values onto the
     * order, so a later price change does not retroactively alter what somebody
     * was charged.
     */
    public record PriceLookupRequest(
            @NotNull @Size(min = 1, max = 100) List<UUID> itemIds
    ) {
    }

    public record PricedItem(
            UUID id,
            String name,
            BigDecimal price,
            boolean available,
            int preparationMinutes
    ) {
    }
}
