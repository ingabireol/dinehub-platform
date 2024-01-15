package com.dinehub.common.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs an event handler at most once per event id.
 *
 * <p>Usage:
 * <pre>{@code
 * idempotency.runOnce(event.meta().eventId(), "payment.completed", () -> {
 *     // handle it
 * });
 * }</pre>
 *
 * <p>The claim is written in its own transaction so that it is committed before
 * the handler runs. If the handler then fails, the message is redelivered and
 * the claim causes it to be skipped — which is the right trade here: an event
 * dropped after a genuine failure is visible in the dead-letter queue, whereas a
 * duplicated payment is not visible at all until a customer complains.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final ProcessedEventRepository repository;

    public IdempotencyService(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    /**
     * @return true if the work ran, false if this event had already been handled
     */
    public boolean runOnce(UUID eventId, String eventType, Runnable work) {
        if (!claim(eventId, eventType)) {
            log.debug("Event {} ({}) already processed — skipping", eventId, eventType);
            return false;
        }
        work.run();
        return true;
    }

    public <T> T runOnce(UUID eventId, String eventType, Supplier<T> work, T alreadyProcessed) {
        if (!claim(eventId, eventType)) {
            log.debug("Event {} ({}) already processed — skipping", eventId, eventType);
            return alreadyProcessed;
        }
        return work.get();
    }

    /**
     * REQUIRES_NEW so the claim commits independently of the caller's transaction.
     * The database's primary key, not an application check, is what makes this
     * race-free when two pods receive the same redelivery.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID eventId, String eventType) {
        if (repository.existsById(eventId)) {
            return false;
        }
        try {
            repository.saveAndFlush(new ProcessedEvent(eventId, eventType));
            return true;
        } catch (DataIntegrityViolationException e) {
            // Another instance claimed it between the check and the insert.
            // That is the expected outcome of a race, not an error.
            log.debug("Event {} claimed concurrently by another instance", eventId);
            return false;
        }
    }
}
