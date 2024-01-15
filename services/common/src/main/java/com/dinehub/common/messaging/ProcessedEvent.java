package com.dinehub.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A record that this service has already handled a given event.
 *
 * <p>RabbitMQ delivers at least once. A consumer that is not idempotent will,
 * sooner or later, process the same message twice — most often during a
 * redeploy, when unacknowledged messages are redelivered to the new pod. For an
 * event like {@code payment.completed} that means charging twice or sending two
 * confirmations.
 *
 * <p>The primary key is the event id, so the uniqueness constraint is the
 * database's job rather than an application-level check with a race in it.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(UUID eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
