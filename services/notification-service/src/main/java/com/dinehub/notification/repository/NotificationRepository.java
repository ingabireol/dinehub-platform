package com.dinehub.notification.repository;

import com.dinehub.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndReadAtIsNull(UUID userId);

    /**
     * Marks every unread notification for a user as read in one statement.
     *
     * <p>A bulk update rather than loading and saving each: a user returning
     * after a week may have hundreds, and fetching them all to set one field
     * each is a lot of work to achieve one UPDATE.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now "
            + "WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllReadForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
