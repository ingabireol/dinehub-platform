package com.dinehub.kitchen.service;

import com.dinehub.kitchen.repository.PendingOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Removes cached order contents for orders that were never paid.
 *
 * <p>Every declined payment leaves a row that no ticket will ever consume.
 * Without this the table grows without bound — slowly enough that nobody
 * notices for a year, and then noticeably.
 */
@Component
public class PendingOrderCleanup {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderCleanup.class);

    private final PendingOrderRepository pendingOrders;
    private final Duration retention;

    public PendingOrderCleanup(PendingOrderRepository pendingOrders,
                               @Value("${dinehub.kitchen.pending-retention:PT6H}") Duration retention) {
        this.pendingOrders = pendingOrders;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${dinehub.kitchen.cleanup-interval:PT1H}")
    @Transactional
    public void prune() {
        int removed = pendingOrders.deleteOlderThan(Instant.now().minus(retention));
        if (removed > 0) {
            log.info("Pruned {} cached order(s) that were never paid", removed);
        }
    }
}
