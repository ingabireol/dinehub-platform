package com.dinehub.kitchen;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.kitchen.entity.KitchenTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KitchenTicketTest {

    private KitchenTicket ticket() {
        return new KitchenTicket(UUID.randomUUID(), UUID.randomUUID(), "2 × Tilapia", 2);
    }

    @Test
    @DisplayName("a new ticket is queued and unclaimed")
    void startsQueued() {
        var ticket = ticket();

        assertThat(ticket.getStatus()).isEqualTo(KitchenTicket.Status.QUEUED);
        assertThat(ticket.getClaimedBy()).isNull();
        assertThat(ticket.getStartedAt()).isNull();
        assertThat(ticket.getQueuedAt()).isNotNull();
    }

    @Test
    @DisplayName("starting a ticket records who claimed it")
    void startRecordsTheChef() {
        // Recording this is what makes "two chefs cooked the same order" visible
        // rather than merely expensive.
        var ticket = ticket();
        UUID chef = UUID.randomUUID();

        ticket.startPreparing(chef);

        assertThat(ticket.getStatus()).isEqualTo(KitchenTicket.Status.PREPARING);
        assertThat(ticket.getClaimedBy()).isEqualTo(chef);
        assertThat(ticket.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("a second chef claiming the same ticket gets a 409")
    void secondClaimConflicts() {
        var ticket = ticket();
        ticket.startPreparing(UUID.randomUUID());

        assertThatThrownBy(() -> ticket.startPreparing(UUID.randomUUID()))
                .isInstanceOf(ApiExceptions.ConflictException.class)
                .hasMessageContaining("PREPARING");
    }

    @Test
    @DisplayName("the lifecycle runs queued → preparing → ready → delivered")
    void fullLifecycle() {
        var ticket = ticket();

        ticket.startPreparing(UUID.randomUUID());
        ticket.markReady();
        assertThat(ticket.getStatus()).isEqualTo(KitchenTicket.Status.READY);
        assertThat(ticket.getReadyAt()).isNotNull();

        ticket.markDelivered();
        assertThat(ticket.getStatus()).isEqualTo(KitchenTicket.Status.DELIVERED);
        assertThat(ticket.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("steps cannot be skipped")
    void cannotSkipSteps() {
        var ticket = ticket();

        assertThatThrownBy(ticket::markReady)
                .isInstanceOf(ApiExceptions.ConflictException.class);
        assertThatThrownBy(ticket::markDelivered)
                .isInstanceOf(ApiExceptions.ConflictException.class);
    }

    @Test
    @DisplayName("a queued ticket can be cancelled")
    void cancelsWhileQueued() {
        var ticket = ticket();

        assertThat(ticket.cancel()).isTrue();
        assertThat(ticket.getStatus()).isEqualTo(KitchenTicket.Status.CANCELLED);
    }

    @Test
    @DisplayName("a ticket being cooked cannot be cancelled away from under the chef")
    void refusesCancellationOnceCooking() {
        // Removing the ticket would leave a chef cooking something nobody is
        // tracking. The caller logs and counts this instead.
        var ticket = ticket();
        ticket.startPreparing(UUID.randomUUID());

        assertThat(ticket.cancel()).isFalse();
        assertThat(ticket.getStatus()).isEqualTo(KitchenTicket.Status.PREPARING);
    }

    @Test
    @DisplayName("preparation time is only available once the ticket is ready")
    void preparationTimeNeedsBothEnds() {
        var ticket = ticket();
        assertThat(ticket.preparationTime()).isNull();

        ticket.startPreparing(UUID.randomUUID());
        assertThat(ticket.preparationTime()).isNull();

        ticket.markReady();
        assertThat(ticket.preparationTime()).isNotNull();
        assertThat(ticket.preparationTime().isNegative()).isFalse();
    }

    @Test
    @DisplayName("waiting time stops counting once a chef picks the ticket up")
    void waitingTimeFreezesOnStart() throws InterruptedException {
        // The board sorts by this. If it kept rising after a chef started, a
        // ticket being cooked would drift to the top and look neglected.
        var ticket = ticket();
        ticket.startPreparing(UUID.randomUUID());
        var frozen = ticket.waitingTime();

        Thread.sleep(20);

        assertThat(ticket.waitingTime()).isEqualTo(frozen);
    }
}
