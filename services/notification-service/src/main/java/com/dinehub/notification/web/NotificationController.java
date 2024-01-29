package com.dinehub.notification.web;

import com.dinehub.notification.dto.NotificationDtos;
import com.dinehub.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Every endpoint is scoped to the authenticated user.
 *
 * <p>There is deliberately no "get notifications for user X" endpoint. Reading
 * somebody else's notifications would expose their order history, so the user id
 * comes from the token and never from the path.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "The authenticated user's own notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    @Operation(summary = "The authenticated user's notifications, newest first")
    public NotificationDtos.PageResponse<NotificationDtos.NotificationResponse> list(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return notificationService.list(userId, PageRequest.of(page, Math.min(size, 100)));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "How many are unread",
            description = "What the bell icon badge polls, so it is a count query, "
                    + "not a page of results the client then counts.")
    public NotificationDtos.UnreadCountResponse unreadCount(@AuthenticationPrincipal UUID userId) {
        return new NotificationDtos.UnreadCountResponse(notificationService.unreadCount(userId));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one as read")
    public NotificationDtos.NotificationResponse markRead(@PathVariable UUID id,
                                                          @AuthenticationPrincipal UUID userId) {
        return notificationService.markRead(id, userId);
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark everything read")
    public ResponseEntity<Map<String, Integer>> markAllRead(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(Map.of("updated", notificationService.markAllRead(userId)));
    }
}
