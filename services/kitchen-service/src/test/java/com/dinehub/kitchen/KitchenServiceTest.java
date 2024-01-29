package com.dinehub.kitchen;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.kitchen.dto.KitchenDtos;
import com.dinehub.kitchen.entity.KitchenTicket;
import com.dinehub.kitchen.entity.PendingOrder;
import com.dinehub.kitchen.repository.KitchenTicketRepository;
import com.dinehub.kitchen.repository.PendingOrderRepository;
import com.dinehub.kitchen.service.KitchenService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KitchenServiceTest {

    @Mock
    private KitchenTicketRepository tickets;

    @Mock
    private PendingOrderRepository pendingOrders;

    @Mock
    private RabbitTemplate rabbit;

    private KitchenService kitchenService;
    private MeterRegistry meterRegistry;
    private UUID orderId;
    private UUID chefId;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        kitchenService = new KitchenService(tickets, pendingOrders, rabbit, meterRegistry);
        orderId = UUID.randomUUID();
        chefId = UUID.randomUUID();
    }

    @Test
    @DisplayName("a paid order produces a queued ticket showing what to cook")
    void paidOrderCreatesTicket() {
        when(tickets.existsByOrderId(orderId)).thenReturn(false);
        when(pendingOrders.findById(orderId)).thenReturn(Optional.of(
                new PendingOrder(orderId, UUID.randomUUID(),
                        "2 × Rwandan Tea, 1 × Grilled Tilapia", 3)));

        kitchenService.createTicketForPaidOrder(orderId, UUID.randomUUID(),
                new BigDecimal("28.00"), 0);

        ArgumentCaptor<KitchenTicket> saved = ArgumentCaptor.forClass(KitchenTicket.class);
        verify(tickets).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(KitchenTicket.Status.QUEUED);
        assertThat(saved.getValue().getItemsSummary())
                .isEqualTo("2 × Rwandan Tea, 1 × Grilled Tilapia");
        assertThat(saved.getValue().getItemCount()).isEqualTo(3);
        // The cache entry is consumed, so the table does not grow forever.
        verify(pendingOrders).deleteById(orderId);
    }

    @Test
    @DisplayName("order.placed caches the contents without creating a ticket")
    void orderPlacedOnlyCaches() {
        // The kitchen must not start cooking before the payment clears.
        kitchenService.rememberOrderContents(orderId, UUID.randomUUID(),
                "1 × Vegetable Curry", 1);

        verify(pendingOrders).save(any(PendingOrder.class));
        verify(tickets, never()).save(any());
    }

    @Test
    @DisplayName("a ticket still appears if payment.completed overtakes order.placed")
    void ticketAppearsEvenWithoutCachedContents() {
        // The two events can race. A legible-but-vague ticket on the board beats
        // a missing one, and the counter makes the race visible.
        when(tickets.existsByOrderId(orderId)).thenReturn(false);
        when(pendingOrders.findById(orderId)).thenReturn(Optional.empty());

        kitchenService.createTicketForPaidOrder(orderId, UUID.randomUUID(),
                new BigDecimal("28.00"), 0);

        ArgumentCaptor<KitchenTicket> saved = ArgumentCaptor.forClass(KitchenTicket.class);
        verify(tickets).save(saved.capture());
        assertThat(saved.getValue().getItemsSummary()).contains("28.00");
        assertThat(meterRegistry.counter("dinehub.kitchen.ticket.without.contents").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a redelivered payment event does not create a second ticket")
    void doesNotDuplicateTickets() {
        // Two tickets for one order means two chefs cooking the same food.
        when(tickets.existsByOrderId(orderId)).thenReturn(true);

        kitchenService.createTicketForPaidOrder(orderId, UUID.randomUUID(),
                new BigDecimal("28.00"), 3);

        verify(tickets, never()).save(any());
    }

    @Test
    @DisplayName("cancelling an order removes a queued ticket from the board")
    void cancellationRemovesQueuedTicket() {
        var ticket = new KitchenTicket(orderId, UUID.randomUUID(), "2 × Tilapia", 2);
        when(tickets.findByOrderId(orderId)).thenReturn(Optional.of(ticket));

        kitchenService.cancelTicketForOrder(orderId);

        assertThat(ticket.getStatus()).isEqualTo(KitchenTicket.Status.CANCELLED);
        verify(tickets).save(ticket);
    }

    @Test
    @DisplayName("cancelling too late leaves the ticket and raises a counter")
    void lateCancellationIsCountedNotSilent() {
        // The food is being cooked for an order that no longer exists. Somebody
        // needs to know, so it is counted and logged rather than swallowed.
        var ticket = new KitchenTicket(orderId, UUID.randomUUID(), "2 × Tilapia", 2);
        ticket.startPreparing(chefId);
        when(tickets.findByOrderId(orderId)).thenReturn(Optional.of(ticket));

        kitchenService.cancelTicketForOrder(orderId);

        assertThat(ticket.getStatus()).isEqualTo(KitchenTicket.Status.PREPARING);
        verify(tickets, never()).save(any());
        assertThat(meterRegistry.counter("dinehub.kitchen.cancelled.too.late").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("cancelling an order with no ticket is harmless")
    void cancellationWithoutTicketIsNoop() {
        when(tickets.findByOrderId(orderId)).thenReturn(Optional.empty());

        kitchenService.cancelTicketForOrder(orderId);

        verify(tickets, never()).save(any());
    }

    @Test
    @DisplayName("the board shows everything still in play, oldest first")
    void boardShowsActiveTickets() {
        var queued = new KitchenTicket(UUID.randomUUID(), UUID.randomUUID(), "A", 1);
        var preparing = new KitchenTicket(UUID.randomUUID(), UUID.randomUUID(), "B", 1);
        preparing.startPreparing(chefId);
        when(tickets.findByStatusInOrderByQueuedAtAsc(any())).thenReturn(List.of(queued, preparing));

        var board = kitchenService.board();

        assertThat(board).hasSize(2);
        ArgumentCaptor<List<KitchenTicket.Status>> statuses =
                ArgumentCaptor.forClass(List.class);
        verify(tickets).findByStatusInOrderByQueuedAtAsc(statuses.capture());
        // Delivered and cancelled tickets must not clutter a working board.
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(KitchenTicket.Status.QUEUED,
                        KitchenTicket.Status.PREPARING, KitchenTicket.Status.READY);
    }

    @Test
    @DisplayName("moving a ticket to PREPARING publishes the change")
    void statusChangePublishes() {
        var ticket = new KitchenTicket(orderId, UUID.randomUUID(), "2 × Tilapia", 2);
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        var response = kitchenService.changeStatus(ticket.getId(), chefId,
                KitchenDtos.StatusChangeRequest.TargetStatus.PREPARING);

        assertThat(response.status()).isEqualTo("PREPARING");
        assertThat(response.claimedBy()).isEqualTo(chefId);

        ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(anyString(), routingKey.capture(), any(Object.class));
        assertThat(routingKey.getValue()).isEqualTo("kitchen.status.updated");
    }

    @Test
    @DisplayName("marking ready records the preparation time metric")
    void readyRecordsPreparationTime() {
        var ticket = new KitchenTicket(orderId, UUID.randomUUID(), "2 × Tilapia", 2);
        ticket.startPreparing(chefId);
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        kitchenService.changeStatus(ticket.getId(), chefId,
                KitchenDtos.StatusChangeRequest.TargetStatus.READY);

        assertThat(meterRegistry.timer("dinehub.kitchen.preparation.duration").count())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("two chefs racing on one ticket produces a clear 409")
    void concurrentClaimConflicts() {
        var ticket = new KitchenTicket(orderId, UUID.randomUUID(), "2 × Tilapia", 2);
        ticket.startPreparing(UUID.randomUUID());
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> kitchenService.changeStatus(ticket.getId(), chefId,
                KitchenDtos.StatusChangeRequest.TargetStatus.PREPARING))
                .isInstanceOf(ApiExceptions.ConflictException.class);
    }

    @Test
    @DisplayName("an unknown ticket is a 404")
    void unknownTicketIsNotFound() {
        UUID ghost = UUID.randomUUID();
        when(tickets.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> kitchenService.getTicket(ghost))
                .isInstanceOf(ApiExceptions.NotFoundException.class);
    }
}
