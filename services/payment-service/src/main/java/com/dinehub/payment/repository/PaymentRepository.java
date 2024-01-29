package com.dinehub.payment.repository;

import com.dinehub.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);

    List<Payment> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    Optional<Payment> findByReference(String reference);

    long countByStatus(Payment.Status status);
}
