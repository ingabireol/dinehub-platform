package com.dinehub.notification.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {

    private NotificationDtos() {
    }

    public record NotificationResponse(
            UUID id,
            String type,
            String title,
            String message,
            UUID relatedOrderId,
            boolean read,
            Instant createdAt
    ) {
    }

    /** An explicit page shape, for the same reason order-service uses one. */
    public record PageResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            boolean last
    ) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages(), page.isLast());
        }
    }

    public record UnreadCountResponse(long unread) {
    }
}
