package com.dinehub.order.repository;

import com.dinehub.order.entity.Order;
import com.dinehub.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Items are fetched with the order. Without the entity graph this is an
     * N+1: one query for the orders, then one per order for its lines.
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);

    @EntityGraph(attributePaths = "items")
    Page<Order> findByCustomerIdOrderByPlacedAtDesc(UUID customerId, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    Page<Order> findByStatusOrderByPlacedAtAsc(OrderStatus status, Pageable pageable);

    @EntityGraph(attributePaths = "items")
    List<Order> findByStatusInOrderByPlacedAtAsc(List<OrderStatus> statuses);

    long countByStatus(OrderStatus status);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.placedAt >= :since")
    long countPlacedSince(@Param("since") Instant since);

    /**
     * Orders stuck awaiting payment.
     *
     * <p>Used by a scheduled sweep: if a {@code payment.completed} event is lost
     * entirely, the order would otherwise sit in PLACED forever with nothing
     * reporting it. This is the query that makes that visible.
     */
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.placedAt < :before")
    List<Order> findStuckIn(@Param("status") OrderStatus status, @Param("before") Instant before);
}
