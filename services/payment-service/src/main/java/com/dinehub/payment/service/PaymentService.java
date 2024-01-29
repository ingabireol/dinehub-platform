package com.dinehub.payment.service;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.common.events.DomainEvents;
import com.dinehub.common.events.EventPayloads;
import com.dinehub.payment.dto.PaymentDtos;
import com.dinehub.payment.entity.Payment;
import com.dinehub.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final RabbitTemplate rabbit;

    private final Counter completed;
    private final Counter failed;

    public PaymentService(PaymentRepository payments, PaymentGateway gateway,
                          RabbitTemplate rabbit, MeterRegistry meterRegistry) {
        this.payments = payments;
        this.gateway = gateway;
        this.rabbit = rabbit;

        this.completed = Counter.builder("dinehub.payments.completed")
                .description("Payments that succeeded")
                .register(meterRegistry);
        this.failed = Counter.builder("dinehub.payments.failed")
                .description("Payments that were declined")
                .register(meterRegistry);
    }

    /**
     * Processes the payment for a newly placed order.
     *
     * <p>Two layers of protection against double-charging, because this is the
     * one place where a duplicate is genuinely expensive:
     *
     * <ol>
     *   <li>The caller wraps this in {@code IdempotencyService}, keyed on the
     *       event id.
     *   <li>This method checks for an existing payment on the order, and the
     *       unique constraint on {@code order_id} catches the race the check
     *       cannot.
     * </ol>
     */
    @Transactional
    public void processOrderPlaced(UUID orderId, UUID customerId,
                                   String customerEmail, BigDecimal amount) {

        if (payments.existsByOrderId(orderId)) {
            // Already charged. Republish the result rather than doing nothing:
            // if the original event was lost, order-service is still waiting.
            payments.findByOrderId(orderId).ifPresent(this::republish);
            log.info("Order {} is already paid — republished the existing result", orderId);
            return;
        }

        PaymentGateway.Result result = gateway.charge(orderId, amount);

        Payment payment = result.successful()
                ? Payment.completed(orderId, customerId, amount)
                : Payment.failed(orderId, customerId, amount, result.failureReason());

        try {
            payments.saveAndFlush(payment);
        } catch (DataIntegrityViolationException e) {
            // Another instance charged this order between the check above and
            // this insert. The constraint is what actually prevents the double
            // charge; this branch just makes the outcome correct.
            log.warn("Concurrent payment attempt for order {} — keeping the first", orderId);
            payments.findByOrderId(orderId).ifPresent(this::republish);
            return;
        }

        if (payment.isSuccessful()) {
            completed.increment();
            log.info("Payment {} completed for order {} ({})",
                    payment.getReference(), orderId, amount);
        } else {
            failed.increment();
            log.info("Payment {} failed for order {}: {}",
                    payment.getReference(), orderId, payment.getFailureReason());
        }

        republish(payment);
    }

    @Transactional(readOnly = true)
    public PaymentDtos.PaymentResponse getByOrder(UUID orderId) {
        return payments.findByOrderId(orderId)
                .map(PaymentService::toResponse)
                .orElseThrow(() -> new ApiExceptions.NotFoundException(
                        "No payment recorded for order " + orderId));
    }

    @Transactional(readOnly = true)
    public List<PaymentDtos.PaymentResponse> listForCustomer(UUID customerId) {
        return payments.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(PaymentService::toResponse)
                .toList();
    }

    private void republish(Payment payment) {
        var meta = EventPayloads.EventMeta.now(MDC.get("traceId"));
        try {
            if (payment.isSuccessful()) {
                rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_COMPLETED,
                        new EventPayloads.PaymentCompleted(meta, payment.getId(),
                                payment.getOrderId(), payment.getCustomerId(),
                                payment.getAmount(), payment.getReference()));
            } else {
                rabbit.convertAndSend(DomainEvents.EXCHANGE, DomainEvents.PAYMENT_FAILED,
                        new EventPayloads.PaymentFailed(meta, payment.getId(),
                                payment.getOrderId(), payment.getCustomerId(),
                                payment.getAmount(), payment.getFailureReason()));
            }
        } catch (Exception e) {
            // The payment record is committed either way. Losing the event leaves
            // the order stuck in PLACED, which the stuck-order sweep reports —
            // whereas failing here would re-deliver the message and risk a second
            // charge attempt.
            log.error("Could not publish the payment result for order {}. "
                    + "The order will appear stuck until this is replayed.",
                    payment.getOrderId(), e);
        }
    }

    static PaymentDtos.PaymentResponse toResponse(Payment payment) {
        return new PaymentDtos.PaymentResponse(
                payment.getId(), payment.getOrderId(), payment.getCustomerId(),
                payment.getAmount(), payment.getStatus().name(), payment.getReference(),
                payment.getFailureReason(), payment.getCreatedAt());
    }
}
