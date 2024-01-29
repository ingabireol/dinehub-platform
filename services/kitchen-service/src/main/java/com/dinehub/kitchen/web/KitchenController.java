package com.dinehub.kitchen.web;

import com.dinehub.common.security.Roles;
import com.dinehub.kitchen.dto.KitchenDtos;
import com.dinehub.kitchen.service.KitchenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kitchen")
@Tag(name = "Kitchen", description = "The kitchen board and ticket lifecycle")
public class KitchenController {

    private final KitchenService kitchenService;

    public KitchenController(KitchenService kitchenService) {
        this.kitchenService = kitchenService;
    }

    @GetMapping("/board")
    @PreAuthorize(Roles.HAS_KITCHEN_OR_ADMIN)
    @Operation(summary = "The kitchen board — everything still in play, oldest first",
            description = "Oldest first is deliberate: a newest-first board leaves one "
                    + "unlucky customer waiting while every later order jumps ahead.")
    public List<KitchenDtos.TicketResponse> board() {
        return kitchenService.board();
    }

    @GetMapping("/tickets/{id}")
    @PreAuthorize(Roles.HAS_KITCHEN_OR_ADMIN)
    @Operation(summary = "One ticket")
    public KitchenDtos.TicketResponse getTicket(@PathVariable UUID id) {
        return kitchenService.getTicket(id);
    }

    @PatchMapping("/tickets/{id}/status")
    @PreAuthorize(Roles.HAS_KITCHEN_OR_ADMIN)
    @Operation(summary = "Move a ticket through the lifecycle",
            description = "PREPARING, READY or DELIVERED only. Returns 409 if the ticket "
                    + "is not in a state that allows the change — which is what two chefs "
                    + "racing on the same ticket looks like.")
    public KitchenDtos.TicketResponse changeStatus(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID chefId,
            @Valid @RequestBody KitchenDtos.StatusChangeRequest request) {
        return kitchenService.changeStatus(id, chefId, request.status());
    }
}
