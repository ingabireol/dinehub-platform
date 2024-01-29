package com.dinehub.kitchen.repository;

import com.dinehub.kitchen.entity.KitchenTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KitchenTicketRepository extends JpaRepository<KitchenTicket, UUID> {

    Optional<KitchenTicket> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);

    /**
     * The board, oldest first.
     *
     * <p>Oldest first is the whole point: a kitchen that works newest-first
     * leaves one unlucky customer waiting indefinitely while every later order
     * jumps ahead.
     */
    List<KitchenTicket> findByStatusInOrderByQueuedAtAsc(List<KitchenTicket.Status> statuses);

    List<KitchenTicket> findByStatusOrderByQueuedAtAsc(KitchenTicket.Status status);

    long countByStatus(KitchenTicket.Status status);
}
