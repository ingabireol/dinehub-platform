package com.dinehub.order.service;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.common.security.Roles;
import com.dinehub.order.client.MenuClient;
import com.dinehub.order.dto.OrderDtos;
import com.dinehub.order.entity.Order;
import com.dinehub.order.entity.OrderItem;
import com.dinehub.order.entity.OrderStatus;
import com.dinehub.order.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final MenuClient menuClient;
    private final RabbitTemplate rabbit;

    // Business metrics. These are what the Grafana business dashboard is built
    // from, and what makes "are orders still flowing?" answerable without
    // querying the database during an incident.
    private final Counter ordersPlaced;
    private final Counter ordersCancelled;
    private final Timer orderPlacementTimer;
    private final MeterRegistry meterRegistry;

    public OrderService(OrderRepository orders, MenuClient menuClient,
                        RabbitTemplate rabbit, MeterRegistry meterRegistry) {
        this.orders = orders;
        this.menuClient = menuClient;
        this.rabbit = rabbit;
        this.meterRegistry = meterRegistry;

        this.ordersPlaced = Counter.builder("dinehub.orders.placed")
                .description("Orders successfully placed")
                .register(meterRegistry);
        this.ordersCancelled = Counter.builder("dinehub.orders.cancelled")
                .description("Orders cancelled")
                .register(meterRegistry);
        this.orderPlacementTimer = Timer.builder("dinehub.orders.placement.duration")
                .description("Time to place an order, including the pricing call")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Transactional
    public OrderDtos.OrderResponse placeOrder(UUID customerId, String customerEmail,
                                              OrderDtos.CreateOrderRequest request) {
        return orderPlacementTimer.record(() -> doPlaceOrder(customerId, customerEmail, request));
    }

    private OrderDtos.OrderResponse doPlaceOrder(UUID customerId, String customerEmail,
                                                 OrderDtos.CreateOrderRequest request) {
        // Collapse duplicate lines for the same item. A client that adds the
        // same dish twice means quantity 2, not two lines — and two lines would
        // produce a confusing receipt and a confusing kitchen ticket.
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (var line : request.items()) {
            quantities.merge(line.menuItemId(), line.quantity(), Integer::sum);
        }

        List<OrderDtos.PricedItem> priced = menuClient.priceItems(List.copyOf(quantities.keySet()));
        Map<UUID, OrderDtos.PricedItem> byId = priced.stream()
                .collect(java.util.stream.Collectors.toMap(OrderDtos.PricedItem::id, p -> p));

        // Every requested item must exist and be available. Silently dropping an
        // unknown item would charge the customer for a different basket than the
        // one they submitted.
        List<String> problems = quantities.keySet().stream()
                .map(id -> {
                    OrderDtos.PricedItem item = byId.get(id);
                    if (item == null) {
                        return "item %s is not on the menu".formatted(id);
                    }
                    if (!item.available()) {
                        return "'%s' is currently unavailable".formatted(item.name());
                    }
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .toList();

        if (!problems.isEmpty()) {
            throw new ApiExceptions.BadRequestException(String.join("; ", problems));
        }

        Order order = new Order(customerId, customerEmail,
                request.deliveryAddress(), request.notes());

        quantities.forEach((itemId, quantity) -> {
            OrderDtos.PricedItem item = byId.get(itemId);
            // Name and price are copied, not referenced. A later price change
            // must not alter what this customer was charged.
            order.addItem(new OrderItem(item.id(), item.name(), item.price(), quantity));
        });

        orders.save(order);
        ordersPlaced.increment();
        log.info("Order {} placed by {} for {} ({} lines)",
                order.getId(), customerId, order.getTotalAmount(), order.getItems().size());

        publish(DomainEvents.ORDER_PLACED, new EventPayloads.OrderPlaced(
                EventPayloads.EventMeta.now(MDC.get("traceId")),
                order.getId(), customerId, customerEmail,
                order.getTotalAmount(), order.getItems().size(), summarise(order)));

        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public OrderDtos.OrderResponse getOrder(UUID orderId, UUID requesterId, String requesterRole) {
        Order order = orders.findWithItemsById(orderId)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Order", orderId));

        // A customer sees only their own orders. Kitchen and admin see all.
        // Without this check, order ids are sequential enough to enumerate.
        boolean isStaff = Roles.KITCHEN.equals(requesterRole) || Roles.ADMIN.equals(requesterRole);
        if (!isStaff && !order.getCustomerId().equals(requesterId)) {
            // 404 rather than 403: confirming the order exists tells an attacker
            // something they should not learn from a probe.
            throw ApiExceptions.NotFoundException.of("Order", orderId);
        }

        return toResponse(order);
    }

    @Transactional(readOnly = true)
    public Page<OrderDtos.OrderResponse> listCustomerOrders(UUID customerId, Pageable pageable) {
        return orders.findByCustomerIdOrderByPlacedAtDesc(customerId, pageable)
                .map(OrderService::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<OrderDtos.OrderResponse> listByStatus(OrderStatus status, Pageable pageable) {
        return orders.findByStatusOrderByPlacedAtAsc(status, pageable)
                .map(OrderService::toResponse);
    }

    @Transactional
    public OrderDtos.OrderResponse cancelOrder(UUID orderId, UUID requesterId,
                                               String requesterRole, String reason) {
        Order order = orders.findWithItemsById(orderId)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Order", orderId));

        boolean isStaff = Roles.KITCHEN.equals(requesterRole) || Roles.ADMIN.equals(requesterRole);
        if (!isStaff && !order.getCustomerId().equals(requesterId)) {
            throw ApiExceptions.NotFoundException.of("Order", orderId);
        }

        // Throws a 409 if the kitchen has already started. The food exists by
        // then and somebody is paying for it.
        order.cancel(reason != null ? reason : "Cancelled by " + (isStaff ? "staff" : "customer"));
        ordersCancelled.increment();
        log.info("Order {} cancelled: {}", orderId, order.getCancellationReason());

        publish(DomainEvents.ORDER_CANCELLED, new EventPayloads.OrderCancelled(
                EventPayloads.EventMeta.now(MDC.get("traceId")),
                order.getId(), order.getCustomerId(), order.getCancellationReason()));

        publishStatusChange(order, OrderStatus.PLACED);
        return toResponse(order);
    }

    /**
     * Applies a status change that arrived as an event.
     *
     * <p>Returns quietly if the transition is not legal. Events arrive out of
     * order under redelivery, and a late {@code payment.completed} must not move
     * a cancelled order back to PAID — but it also must not dead-letter, because
     * nothing is actually wrong.
     */
    @Transactional
    public boolean applyStatusFromEvent(UUID orderId, OrderStatus next) {
        Order order = orders.findWithItemsById(orderId).orElse(null);
        if (order == null) {
            log.warn("Event referenced order {}, which does not exist", orderId);
            return false;
        }

        OrderStatus previous = order.getStatus();
        if (!order.tryTransitionTo(next)) {
            log.info("Ignoring {} → {} for order {}: not a legal transition",
                    previous, next, orderId);
            return false;
        }

        orders.save(order);
        log.info("Order {} moved {} → {}", orderId, previous, next);
        publishStatusChange(order, previous);

        meterRegistry.counter("dinehub.orders.status.changed",
                "from", previous.name(), "to", next.name()).increment();
        return true;
    }

    @Transactional
    public void cancelFromFailedPayment(UUID orderId, String reason) {
        Order order = orders.findWithItemsById(orderId).orElse(null);
        if (order == null || order.getStatus().isTerminal()) {
            return;
        }
        OrderStatus previous = order.getStatus();
        order.cancel("Payment failed: " + reason);
        orders.save(order);
        ordersCancelled.increment();
        log.info("Order {} cancelled because payment failed: {}", orderId, reason);
        publishStatusChange(order, previous);
    }

    private void publishStatusChange(Order order, OrderStatus previous) {
        publish(DomainEvents.ORDER_STATUS_CHANGED, new EventPayloads.OrderStatusChanged(
                EventPayloads.EventMeta.now(MDC.get("traceId")),
                order.getId(), order.getCustomerId(),
                previous.name(), order.getStatus().name()));
    }

    private void publish(String routingKey, Object payload) {
        try {
            rabbit.convertAndSend(DomainEvents.EXCHANGE, routingKey, payload);
        } catch (Exception e) {
            // The order is already committed. Losing the event means a downstream
            // service misses a transition, which the stuck-order sweep and the
            // dead-letter alert will surface — whereas rolling back a placed
            // order because the broker blinked would lose the customer's order.
            log.error("Could not publish {} — downstream services will not see it", routingKey, e);
        }
    }

    /**
     * "2 × Rwandan Tea, 1 × Grilled Tilapia" — what a chef needs to read.
     *
     * <p>Built here and carried on the event so that kitchen-service never has
     * to call back for it.
     */
    private static String summarise(Order order) {
        return order.getItems().stream()
                .map(i -> "%d × %s".formatted(i.getQuantity(), i.getItemName()))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    static OrderDtos.OrderResponse toResponse(Order order) {
        return new OrderDtos.OrderResponse(
                order.getId(), order.getCustomerId(), order.getCustomerEmail(),
                order.getStatus().name(), order.getTotalAmount(), order.getDeliveryAddress(),
                order.getNotes(), order.getCancellationReason(),
                order.getPlacedAt(), order.getUpdatedAt(),
                order.getItems().stream()
                        .map(i -> new OrderDtos.OrderLineResponse(
                                i.getMenuItemId(), i.getItemName(), i.getUnitPrice(),
                                i.getQuantity(), i.lineTotal()))
                        .toList());
    }
}
