package com.dinehub.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotEmpty(message = "an order must contain at least one item")
            @Size(max = 50, message = "an order may contain at most 50 lines")
            @Valid
            List<OrderLineRequest> items,

            @Size(max = 400) String deliveryAddress,
            @Size(max = 500) String notes
    ) {
    }

    public record OrderLineRequest(
            @NotNull UUID menuItemId,

            // Bounded. An unbounded quantity is both a pricing accident waiting
            // to happen and a trivial way to make the kitchen queue useless.
            @Min(value = 1, message = "quantity must be at least 1")
            @Max(value = 50, message = "quantity must not exceed 50")
            int quantity
    ) {
    }

    public record CancelOrderRequest(
            @Size(max = 300) String reason
    ) {
    }

    public record OrderResponse(
            UUID id,
            UUID customerId,
            String customerEmail,
            String status,
            BigDecimal totalAmount,
            String deliveryAddress,
            String notes,
            String cancellationReason,
            Instant placedAt,
            Instant updatedAt,
            List<OrderLineResponse> items
    ) {
    }

    /**
     * An explicit page shape.
     *
     * <p>Returning Spring Data's {@code Page} straight out of a controller leaks
     * its internal structure into the public API — and Spring Boot 3.3 onwards
     * warns about exactly that, because the shape is not stable across versions.
     * A small record pins the contract the Angular client codes against.
     */
    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
            return new PageResponse<>(
                    page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages(),
                    page.isFirst(), page.isLast());
        }
    }

    public record OrderLineResponse(
            UUID menuItemId,
            String itemName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal
    ) {
    }

    /** What menu-service returns from its pricing endpoint. */
    public record PricedItem(
            UUID id,
            String name,
            BigDecimal price,
            boolean available,
            int preparationMinutes
    ) {
    }
}
