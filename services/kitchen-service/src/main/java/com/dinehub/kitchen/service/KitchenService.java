package com.dinehub.kitchen.service;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.kitchen.dto.KitchenDtos;
import com.dinehub.kitchen.entity.KitchenTicket;
import com.dinehub.kitchen.entity.PendingOrder;
import com.dinehub.kitchen.repository.KitchenTicketRepository;
import com.dinehub.kitchen.repository.PendingOrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class KitchenService {

    private static final Logger log = LoggerFactory.getLogger(KitchenService.class);

    private final KitchenTicketRepository tickets;
    private final PendingOrderRepository pendingOrders;
    private final RabbitTemplate rabbit;
    private final MeterRegistry meterRegistry;
    private final Timer preparationTimer;

    public KitchenService(KitchenTicketRepository tickets,
                          PendingOrderRepository pendingOrders,
                          RabbitTemplate rabbit,
                          MeterRegistry meterRegistry) {
        this.tickets = tickets;
        this.pendingOrders = pendingOrders;
        this.rabbit = rabbit;
        this.meterRegistry = meterRegistry;

        // The business metric the kitchen actually cares about: how long food
        // takes once someone starts cooking it.
        this.preparationTimer = Timer.builder("dinehub.kitchen.preparation.duration")
                .description("Time from a chef starting a ticket to marking it ready")
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry);

        io.micrometer.core.instrument.Gauge
                .builder("dinehub.kitchen.queue.depth",
                        () -> tickets.countByStatus(KitchenTicket.Status.QUEUED))
                .description("Tickets waiting for a chef to pick them up")
                .register(meterRegistry);
    }

    /**
     * Creates a ticket for a paid order.
     *
     * <p>Triggered by {@code payment.completed}, never by {@code order.placed}:
     * cooking food that has not been paid for is a mistake that only has to
     * happen a few times to matter.
     */
    /**
     * Remembers what an order contains, from {@code order.placed}.
     *
     * <p>No ticket is created here: the kitchen must not start cooking before
     * the payment clears.
     */
    @Transactional
    public void rememberOrderContents(UUID orderId, UUID customerId,
                                      String itemsSummary, int itemCount) {
        pendingOrders.save(new PendingOrder(orderId, customerId, itemsSummary, itemCount));
        log.debug("Noted contents of order {} ({} items), awaiting payment", orderId, itemCount);
    }

    /**
     * Creates the ticket once payment has cleared.
     *
     * <p>Uses the summary cached from {@code order.placed}. If that has not
     * arrived yet — the two events can race — the ticket still appears, with the
     * amount instead of the dish names. A legible-but-incomplete ticket on the
     * board beats a missing one.
     */
    @Transactional
    public void createTicketForPaidOrder(UUID orderId, UUID customerId,
                                         BigDecimal amount, int itemCountHint) {
        if (tickets.existsByOrderId(orderId)) {
            log.debug("Ticket for order {} already exists", orderId);
            return;
        }

        var pending = pendingOrders.findById(orderId).orElse(null);
        String summary = pending != null && !pending.getItemsSummary().isBlank()
                ? pending.getItemsSummary()
                : "Order total %s — contents not yet received".formatted(amount);
        int itemCount = pending != null ? pending.getItemCount() : itemCountHint;

        if (pending == null) {
            // Rare, and worth knowing about: it means payment.completed overtook
            // order.placed, or order.placed was lost.
            log.warn("No cached contents for order {} — the ticket will be vague", orderId);
            meterRegistry.counter("dinehub.kitchen.ticket.without.contents").increment();
        }

        var ticket = new KitchenTicket(orderId, customerId, summary, itemCount);
        tickets.save(ticket);
        pendingOrders.deleteById(orderId);

        log.info("Ticket {} queued for order {}: {}", ticket.getId(), orderId, summary);
    }

    @Transactional
    public void cancelTicketForOrder(UUID orderId) {
        tickets.findByOrderId(orderId).ifPresent(ticket -> {
            if (ticket.cancel()) {
                tickets.save(ticket);
                log.info("Ticket {} cancelled because order {} was cancelled",
                        ticket.getId(), orderId);
            } else {
                // The chef has already started. Someone needs to know the food
                // is being cooked for an order that no longer exists.
                log.warn("Order {} was cancelled but ticket {} is already {} — "
                                + "the kitchen is still cooking it",
                        orderId, ticket.getId(), ticket.getStatus());
                meterRegistry.counter("dinehub.kitchen.cancelled.too.late").increment();
            }
        });
    }

    @Transactional(readOnly = true)
    public List<KitchenDtos.TicketResponse> board() {
        // Everything still in play, oldest first.
        return tickets.findByStatusInOrderByQueuedAtAsc(List.of(
                        KitchenTicket.Status.QUEUED,
                        KitchenTicket.Status.PREPARING,
                        KitchenTicket.Status.READY))
                .stream()
                .map(KitchenService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public KitchenDtos.TicketResponse getTicket(UUID ticketId) {
        return tickets.findById(ticketId)
                .map(KitchenService::toResponse)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Ticket", ticketId));
    }

    @Transactional
    public KitchenDtos.TicketResponse changeStatus(
            UUID ticketId, UUID chefId, KitchenDtos.StatusChangeRequest.TargetStatus target) {

        KitchenTicket ticket = tickets.findById(ticketId)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of("Ticket", ticketId));

        // Each of these throws a 409 if the ticket is not in the right state, so
        // two chefs racing on the same ticket produces a clear conflict rather
        // than a silently lost update.
        switch (target) {
            case PREPARING -> ticket.startPreparing(chefId);
            case READY -> {
                ticket.markReady();
                var duration = ticket.preparationTime();
                if (duration != null) {
                    preparationTimer.record(duration);
                }
            }
            case DELIVERED -> ticket.markDelivered();
        }

        tickets.save(ticket);
        log.info("Ticket {} (order {}) moved to {} by {}",
                ticketId, ticket.getOrderId(), ticket.getStatus(), chefId);

        publishStatus(ticket, chefId);
        return toResponse(ticket);
    }

    private void publishStatus(KitchenTicket ticket, UUID chefId) {
        // The kitchen's status names map onto the order lifecycle one for one,
        // so order-service can apply them without a translation table.
        var event = new EventPayloads.KitchenStatusUpdated(
                EventPayloads.EventMeta.now(MDC.get("traceId")),
                ticket.getOrderId(), ticket.getStatus().name(), chefId);
        try {
            rabbit.convertAndSend(DomainEvents.EXCHANGE,
                    DomainEvents.KITCHEN_STATUS_UPDATED, event);
        } catch (Exception e) {
            log.error("Could not publish kitchen.status.updated for order {} — "
                    + "the customer will not see this change", ticket.getOrderId(), e);
        }
    }

    static KitchenDtos.TicketResponse toResponse(KitchenTicket ticket) {
        var preparation = ticket.preparationTime();
        return new KitchenDtos.TicketResponse(
                ticket.getId(), ticket.getOrderId(), ticket.getItemsSummary(),
                ticket.getItemCount(), ticket.getStatus().name(), ticket.getClaimedBy(),
                ticket.getQueuedAt(), ticket.getStartedAt(), ticket.getReadyAt(),
                ticket.waitingTime().toSeconds(),
                preparation != null ? preparation.toSeconds() : null);
    }
}
