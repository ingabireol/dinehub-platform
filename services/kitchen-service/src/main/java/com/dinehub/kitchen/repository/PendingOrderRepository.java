package com.dinehub.kitchen.repository;

import com.dinehub.kitchen.entity.PendingOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface PendingOrderRepository extends JpaRepository<PendingOrder, UUID> {

    /**
     * Removes rows for orders that were never paid.
     *
     * <p>Without this the table grows forever: every declined payment leaves a
     * row that no ticket will ever consume.
     */
    @Modifying
    @Query("DELETE FROM PendingOrder p WHERE p.recordedAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
