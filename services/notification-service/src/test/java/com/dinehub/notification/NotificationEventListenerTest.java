package com.dinehub.notification;

import com.dinehub.common.events.EventPayloads;
import com.dinehub.common.messaging.IdempotencyService;
import com.dinehub.notification.entity.Notification;
import com.dinehub.notification.messaging.NotificationEventListener;
import com.dinehub.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private IdempotencyService idempotency;

    private NotificationEventListener listener;
    private UUID userId;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        listener = new NotificationEventListener(notificationService, idempotency);
        userId = UUID.randomUUID();
        orderId = UUID.randomUUID();

        doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return true;
        }).when(idempotency).runOnce(any(UUID.class), anyString(), any(Runnable.class));
    }

    private EventPayloads.EventMeta meta() {
        return EventPayloads.EventMeta.now("trace-abc");
    }

    @Test
    @DisplayName("a new registration produces a welcome message using the first name")
    void welcomesNewUser() {
        listener.onUserRegistered(new EventPayloads.UserRegistered(
                meta(), userId, "new@dinehub.local", "Grace Mukamana", "CUSTOMER"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).record(any(), any(), anyString(), message.capture(), any());
        assertThat(message.getValue()).contains("Grace").doesNotContain("Mukamana");
    }

    @Test
    @DisplayName("a registration with no name still reads sensibly")
    void handlesMissingName() {
        // "Hello  — your account is ready" would look broken to a real user.
        listener.onUserRegistered(new EventPayloads.UserRegistered(
                meta(), userId, "new@dinehub.local", "", "CUSTOMER"));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).record(any(), any(), anyString(), message.capture(), any());
        assertThat(message.getValue()).contains("Hello there");
    }

    @ParameterizedTest(name = "{0} produces a {1} notification")
    @CsvSource({
            "PAID, ORDER_PAID",
            "PREPARING, ORDER_PREPARING",
            "READY, ORDER_READY",
            "DELIVERED, ORDER_DELIVERED",
            "CANCELLED, ORDER_CANCELLED",
    })
    @DisplayName("each customer-visible status gets its own wording")
    void mapsStatusesToNotifications(String status, Notification.Type expected) {
        listener.onOrderStatusChanged(new EventPayloads.OrderStatusChanged(
                meta(), orderId, userId, "PLACED", status));

        verify(notificationService).record(any(), org.mockito.ArgumentMatchers.eq(expected),
                anyString(), anyString(), org.mockito.ArgumentMatchers.eq(orderId));
    }

    @Test
    @DisplayName("PLACED produces no notification")
    void placedProducesNothing() {
        // The customer has just pressed the button and is looking at the
        // confirmation screen. A notification per internal state change trains
        // people to ignore notifications.
        listener.onOrderStatusChanged(new EventPayloads.OrderStatusChanged(
                meta(), orderId, userId, null, "PLACED"));

        verify(notificationService, never()).record(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an unrecognised status is ignored rather than dead-lettered")
    void unknownStatusIsIgnored() {
        // A status this service has no wording for is not a failure — the order
        // still progressed. Throwing would fill the DLQ with messages nobody
        // needs to act on.
        listener.onOrderStatusChanged(new EventPayloads.OrderStatusChanged(
                meta(), orderId, userId, "PAID", "SOMETHING_NEW"));

        verify(notificationService, never()).record(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("the notification is addressed to the customer, not the order")
    void addressesTheCustomer() {
        listener.onOrderStatusChanged(new EventPayloads.OrderStatusChanged(
                meta(), orderId, userId, "PLACED", "PAID"));

        verify(notificationService).record(
                org.mockito.ArgumentMatchers.eq(userId), any(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.eq(orderId));
    }
}
