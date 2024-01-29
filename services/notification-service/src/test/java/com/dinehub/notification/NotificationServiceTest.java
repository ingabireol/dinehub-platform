package com.dinehub.notification;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.notification.entity.Notification;
import com.dinehub.notification.repository.NotificationRepository;
import com.dinehub.notification.service.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notifications;

    private NotificationService notificationService;
    private MeterRegistry meterRegistry;
    private UUID userId;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        notificationService = new NotificationService(notifications, meterRegistry);
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("recording a notification persists it and counts it by type")
    void recordsNotification() {
        notificationService.record(userId, Notification.Type.ORDER_READY,
                "Your order is ready", "Collect it at the counter", UUID.randomUUID());

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        org.mockito.Mockito.verify(notifications).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(Notification.Type.ORDER_READY);
        assertThat(saved.getValue().isRead()).isFalse();

        assertThat(meterRegistry.counter("dinehub.notifications.created",
                "type", "ORDER_READY").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("listing is scoped to the user and paged")
    void listsForUser() {
        when(notifications.findByUserIdOrderByCreatedAtDesc(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(
                        new Notification(userId, Notification.Type.WELCOME,
                                "Welcome", "Hello", null))));

        var page = notificationService.list(userId, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().title()).isEqualTo("Welcome");
    }

    @Test
    @DisplayName("marking read is scoped to the owner")
    void markReadIsScopedToOwner() {
        // Scoped by user id, not just notification id. Otherwise anyone could
        // mark anyone's notifications read — and the lookup itself would confirm
        // which ids exist.
        UUID notificationId = UUID.randomUUID();
        when(notifications.findByIdAndUserId(notificationId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markRead(notificationId, userId))
                .isInstanceOf(ApiExceptions.NotFoundException.class);
    }

    @Test
    @DisplayName("marking read twice keeps the first read time")
    void markReadIsIdempotent() {
        // The client marks notifications read as the user scrolls, which sends
        // the same request more than once.
        var notification = new Notification(userId, Notification.Type.ORDER_PAID,
                "Payment received", "Thank you", UUID.randomUUID());
        when(notifications.findByIdAndUserId(notification.getId(), userId))
                .thenReturn(Optional.of(notification));

        notificationService.markRead(notification.getId(), userId);
        Instant firstRead = notification.getReadAt();

        notificationService.markRead(notification.getId(), userId);

        assertThat(notification.getReadAt()).isEqualTo(firstRead);
    }

    @Test
    @DisplayName("mark-all-read is a single statement, not a read-modify-write loop")
    void markAllReadIsBulk() {
        // A user returning after a week may have hundreds. Loading them all to
        // set one field each is a lot of work to achieve one UPDATE.
        when(notifications.markAllReadForUser(eq(userId), any())).thenReturn(42);

        assertThat(notificationService.markAllRead(userId)).isEqualTo(42);
        org.mockito.Mockito.verify(notifications).markAllReadForUser(eq(userId), any());
        org.mockito.Mockito.verify(notifications, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("the unread count is a count query")
    void unreadCountIsACount() {
        // The bell badge polls this. Returning a page and counting it client-side
        // would transfer every notification on every poll.
        when(notifications.countByUserIdAndReadAtIsNull(userId)).thenReturn(7L);

        assertThat(notificationService.unreadCount(userId)).isEqualTo(7L);
    }
}
