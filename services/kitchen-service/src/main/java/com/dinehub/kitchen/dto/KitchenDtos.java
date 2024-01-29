package com.dinehub.kitchen.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class KitchenDtos {

    private KitchenDtos() {
    }

    public record TicketResponse(
            UUID id,
            UUID orderId,
            String itemsSummary,
            int itemCount,
            String status,
            UUID claimedBy,
            Instant queuedAt,
            Instant startedAt,
            Instant readyAt,
            long waitingSeconds,
            Long preparationSeconds
    ) {
    }

    public record StatusChangeRequest(
            @NotNull TargetStatus status
    ) {
        /**
         * Only the transitions the board actually offers.
         *
         * <p>Deliberately not the full {@code KitchenTicket.Status} enum:
         * accepting QUEUED or CANCELLED here would let a chef put a ticket back
         * or cancel a paid order from the board, neither of which is a kitchen
         * decision.
         */
        public enum TargetStatus {
            PREPARING, READY, DELIVERED
        }
    }
}
