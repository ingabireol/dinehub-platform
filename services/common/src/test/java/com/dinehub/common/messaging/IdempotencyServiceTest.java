package com.dinehub.common.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the control that stops a redelivered message being processed twice.
 *
 * <p>This matters more than its size suggests: RabbitMQ guarantees at-least-once
 * delivery, so every consumer <em>will</em> see a duplicate eventually, usually
 * during a redeploy. For {@code payment.completed} that means charging twice.
 */
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private ProcessedEventRepository repository;

    private IdempotencyService idempotency;
    private UUID eventId;

    @BeforeEach
    void setUp() {
        idempotency = new IdempotencyService(repository);
        eventId = UUID.randomUUID();
    }

    @Test
    @DisplayName("runs the handler the first time an event is seen")
    void runsOnFirstDelivery() {
        when(repository.existsById(eventId)).thenReturn(false);
        when(repository.saveAndFlush(any(ProcessedEvent.class))).thenAnswer(i -> i.getArgument(0));

        AtomicInteger runs = new AtomicInteger();
        boolean ran = idempotency.runOnce(eventId, "payment.completed", runs::incrementAndGet);

        assertThat(ran).isTrue();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("skips the handler when the event has already been processed")
    void skipsOnRedelivery() {
        when(repository.existsById(eventId)).thenReturn(true);

        AtomicInteger runs = new AtomicInteger();
        boolean ran = idempotency.runOnce(eventId, "payment.completed", runs::incrementAndGet);

        assertThat(ran).isFalse();
        assertThat(runs.get()).isZero();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a concurrent claim by another instance is not an error")
    void handlesConcurrentClaim() {
        // Two pods receive the same redelivery. The database primary key
        // decides; the loser skips quietly rather than failing the message.
        when(repository.existsById(eventId)).thenReturn(false);
        when(repository.saveAndFlush(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        AtomicInteger runs = new AtomicInteger();
        boolean ran = idempotency.runOnce(eventId, "order.placed", runs::incrementAndGet);

        assertThat(ran).isFalse();
        assertThat(runs.get()).isZero();
    }

    @Test
    @DisplayName("the supplier form returns the fallback when already processed")
    void supplierFormReturnsFallback() {
        when(repository.existsById(eventId)).thenReturn(true);

        String result = idempotency.runOnce(eventId, "order.placed",
                () -> "fresh", "already-done");

        assertThat(result).isEqualTo("already-done");
    }

    @Test
    @DisplayName("the supplier form returns the computed value on first delivery")
    void supplierFormReturnsComputedValue() {
        when(repository.existsById(eventId)).thenReturn(false);
        when(repository.saveAndFlush(any(ProcessedEvent.class))).thenAnswer(i -> i.getArgument(0));

        String result = idempotency.runOnce(eventId, "order.placed",
                () -> "fresh", "already-done");

        assertThat(result).isEqualTo("fresh");
    }

    @Test
    @DisplayName("the stored record carries the event type and a timestamp")
    void recordsWhatWasProcessed() {
        ProcessedEvent record = new ProcessedEvent(eventId, "kitchen.status.updated");

        assertThat(record.getEventId()).isEqualTo(eventId);
        assertThat(record.getEventType()).isEqualTo("kitchen.status.updated");
        assertThat(record.getProcessedAt()).isNotNull();
    }
}
