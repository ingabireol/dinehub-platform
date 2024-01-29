package com.dinehub.order.web;

import com.dinehub.common.security.Roles;
import com.dinehub.order.dto.OrderDtos;
import com.dinehub.order.entity.OrderStatus;
import com.dinehub.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Placing, viewing and cancelling orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @PreAuthorize(Roles.HAS_CUSTOMER)
    @Operation(summary = "Place an order",
            description = "Prices the basket against the live menu and snapshots those prices "
                    + "onto the order. A later price change does not alter this order.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order placed"),
            @ApiResponse(responseCode = "400", description = "An item is unknown or unavailable")
    })
    public ResponseEntity<OrderDtos.OrderResponse> placeOrder(
            @AuthenticationPrincipal UUID customerId,
            @RequestHeader(value = "X-User-Email", required = false) String email,
            @Valid @RequestBody OrderDtos.CreateOrderRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.placeOrder(customerId,
                        email != null ? email : "unknown@dinehub.local", request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one order",
            description = "A customer may see only their own orders; kitchen and admin see all.")
    public OrderDtos.OrderResponse getOrder(@PathVariable UUID id,
                                            @AuthenticationPrincipal UUID requesterId,
                                            @RequestHeader(value = "X-User-Role",
                                                    defaultValue = "CUSTOMER") String role) {
        return orderService.getOrder(id, requesterId, role);
    }

    @GetMapping("/mine")
    @PreAuthorize(Roles.HAS_CUSTOMER)
    @Operation(summary = "The authenticated customer's own orders, newest first")
    public OrderDtos.PageResponse<OrderDtos.OrderResponse> myOrders(
            @AuthenticationPrincipal UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // Capped. An unbounded page size is a trivial way to make the service
        // load every order it has ever taken into memory.
        return OrderDtos.PageResponse.of(orderService.listCustomerOrders(
                customerId, PageRequest.of(page, Math.min(size, 100))));
    }

    @GetMapping
    @PreAuthorize(Roles.HAS_KITCHEN_OR_ADMIN)
    @Operation(summary = "Orders by status (KITCHEN or ADMIN)")
    public OrderDtos.PageResponse<OrderDtos.OrderResponse> listByStatus(
            @RequestParam OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return OrderDtos.PageResponse.of(
                orderService.listByStatus(status, PageRequest.of(page, Math.min(size, 100))));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an order",
            description = "Allowed only before the kitchen starts preparing. After that the "
                    + "food exists and cancellation is a refund conversation, not a status change.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled"),
            @ApiResponse(responseCode = "409", description = "Too late to cancel")
    })
    public OrderDtos.OrderResponse cancelOrder(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID requesterId,
            @RequestHeader(value = "X-User-Role", defaultValue = "CUSTOMER") String role,
            @Valid @RequestBody(required = false) OrderDtos.CancelOrderRequest request) {

        return orderService.cancelOrder(id, requesterId, role,
                request != null ? request.reason() : null);
    }
}
