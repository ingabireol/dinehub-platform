package com.dinehub.notification.service;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.notification.dto.NotificationDtos;
import com.dinehub.notification.entity.Notification;
import com.dinehub.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final MeterRegistry meterRegistry;

    public NotificationService(NotificationRepository notifications, MeterRegistry meterRegistry) {
        this.notifications = notifications;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void record(UUID userId, Notification.Type type, String title,
                       String message, UUID relatedOrderId) {
        notifications.save(new Notification(userId, type, title, message, relatedOrderId));
        meterRegistry.counter("dinehub.notifications.created", "type", type.name()).increment();

        // The "simulated email and SMS" the brief asks for. Logged rather than
        // sent, and said plainly: pretending to integrate with a provider that
        // is not there would make the logs lie.
        log.info("[SIMULATED EMAIL] to user {} — {}: {}", userId, title, message);
        log.info("[SIMULATED SMS]   to user {} — {}", userId, title);
    }

    @Transactional(readOnly = true)
    public NotificationDtos.PageResponse<NotificationDtos.NotificationResponse> list(
            UUID userId, Pageable pageable) {
        return NotificationDtos.PageResponse.of(
                notifications.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                        .map(NotificationService::toResponse));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public NotificationDtos.NotificationResponse markRead(UUID notificationId, UUID userId) {
        // Scoped by user id, not just notification id. Without that scoping,
        // anyone could mark anyone else's notifications read — and, more to the
        // point, the lookup would confirm which ids exist.
        Notification notification = notifications.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> ApiExceptions.NotFoundException.of(
                        "Notification", notificationId));

        notification.markRead();
        notifications.save(notification);
        return toResponse(notification);
    }

    @Transactional
    public int markAllRead(UUID userId) {
        int updated = notifications.markAllReadForUser(userId, Instant.now());
        log.debug("Marked {} notification(s) read for user {}", updated, userId);
        return updated;
    }

    static NotificationDtos.NotificationResponse toResponse(Notification n) {
        return new NotificationDtos.NotificationResponse(
                n.getId(), n.getType().name(), n.getTitle(), n.getMessage(),
                n.getRelatedOrderId(), n.isRead(), n.getCreatedAt());
    }
}
