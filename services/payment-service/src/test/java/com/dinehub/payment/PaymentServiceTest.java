package com.dinehub.payment;

import com.dinehub.common.api.ApiExceptions;
import com.dinehub.payment.entity.Payment;
import com.dinehub.payment.repository.PaymentRepository;
import com.dinehub.payment.service.PaymentGateway;
import com.dinehub.payment.service.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private PaymentRepository payments;

    @Mock
    private PaymentGateway gateway;

    @Mock
    private RabbitTemplate rabbit;

    private PaymentService paymentService;
    private MeterRegistry meterRegistry;
    private UUID orderId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        paymentService = new PaymentService(payments, gateway, rabbit, meterRegistry);
        orderId = UUID.randomUUID();
        customerId = UUID.randomUUID();
    }

    @Test
    @DisplayName("a successful charge records the payment and publishes completion")
    void successfulPayment() {
        when(payments.existsByOrderId(orderId)).thenReturn(false);
        when(gateway.charge(any(), any())).thenReturn(PaymentGateway.Result.success());
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        paymentService.processOrderPlaced(orderId, customerId, "c@dinehub.local",
                new BigDecimal("28.00"));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isSuccessful()).isTrue();
        assertThat(saved.getValue().getReference()).startsWith("PAY-");

        ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(anyString(), routingKey.capture(), any(Object.class));
        assertThat(routingKey.getValue()).isEqualTo("payment.completed");
        assertThat(meterRegistry.counter("dinehub.payments.completed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a declined charge records the reason and publishes failure")
    void declinedPayment() {
        when(payments.existsByOrderId(orderId)).thenReturn(false);
        when(gateway.charge(any(), any()))
                .thenReturn(PaymentGateway.Result.declined("Card declined by issuer"));
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));

        paymentService.processOrderPlaced(orderId, customerId, "c@dinehub.local",
                new BigDecimal("28.00"));

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(payments).saveAndFlush(saved.capture());
        assertThat(saved.getValue().isSuccessful()).isFalse();
        assertThat(saved.getValue().getFailureReason()).isEqualTo("Card declined by issuer");

        ArgumentCaptor<String> routingKey = ArgumentCaptor.forClass(String.class);
        verify(rabbit).convertAndSend(anyString(), routingKey.capture(), any(Object.class));
        assertThat(routingKey.getValue()).isEqualTo("payment.failed");
        assertThat(meterRegistry.counter("dinehub.payments.failed").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("an order that is already paid is not charged again")
    void doesNotChargeTwice() {
        // The expensive failure this service exists to prevent.
        var existing = Payment.completed(orderId, customerId, new BigDecimal("28.00"));
        when(payments.existsByOrderId(orderId)).thenReturn(true);
        when(payments.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        paymentService.processOrderPlaced(orderId, customerId, "c@dinehub.local",
                new BigDecimal("28.00"));

        verify(gateway, never()).charge(any(), any());
        verify(payments, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an already-paid order republishes its result rather than going silent")
    void republishesExistingResult() {
        // If the original event was lost, order-service is still waiting. Doing
        // nothing here would leave the order stuck in PLACED forever.
        var existing = Payment.completed(orderId, customerId, new BigDecimal("28.00"));
        when(payments.existsByOrderId(orderId)).thenReturn(true);
        when(payments.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        paymentService.processOrderPlaced(orderId, customerId, "c@dinehub.local",
                new BigDecimal("28.00"));

        verify(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("a concurrent charge loses to the database constraint, not to a double charge")
    void handlesConcurrentCharge() {
        // Two instances receive the same redelivery. The unique constraint on
        // order_id decides; the loser republishes the winner's result.
        var winner = Payment.completed(orderId, customerId, new BigDecimal("28.00"));
        when(payments.existsByOrderId(orderId)).thenReturn(false);
        when(gateway.charge(any(), any())).thenReturn(PaymentGateway.Result.success());
        when(payments.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key: order_id"));
        when(payments.findByOrderId(orderId)).thenReturn(Optional.of(winner));

        paymentService.processOrderPlaced(orderId, customerId, "c@dinehub.local",
                new BigDecimal("28.00"));

        // Exactly one result published, and it is the committed one.
        verify(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));
        assertThat(meterRegistry.counter("dinehub.payments.completed").count()).isZero();
    }

    @Test
    @DisplayName("the payment record survives the broker being down")
    void survivesBrokerOutage() {
        // The charge already happened. Failing here would redeliver the message
        // and risk a second charge attempt — much worse than a lost event, which
        // the stuck-order sweep reports.
        when(payments.existsByOrderId(orderId)).thenReturn(false);
        when(gateway.charge(any(), any())).thenReturn(PaymentGateway.Result.success());
        when(payments.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
        doThrow(new AmqpException("broker down"))
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class));

        paymentService.processOrderPlaced(orderId, customerId, "c@dinehub.local",
                new BigDecimal("28.00"));

        verify(payments).saveAndFlush(any(Payment.class));
    }

    @Test
    @DisplayName("looking up a payment for an unknown order is a 404")
    void unknownOrderIsNotFound() {
        when(payments.findByOrderId(orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getByOrder(orderId))
                .isInstanceOf(ApiExceptions.NotFoundException.class)
                .hasMessageContaining(orderId.toString());
    }

    @Test
    @DisplayName("a customer's payment history comes back newest first")
    void listsCustomerPayments() {
        when(payments.findByCustomerIdOrderByCreatedAtDesc(customerId)).thenReturn(
                java.util.List.of(
                        Payment.completed(UUID.randomUUID(), customerId, new BigDecimal("10.00")),
                        Payment.failed(UUID.randomUUID(), customerId,
                                new BigDecimal("20.00"), "Insufficient funds")));

        var history = paymentService.listForCustomer(customerId);

        assertThat(history).hasSize(2);
        assertThat(history.get(1).failureReason()).isEqualTo("Insufficient funds");
    }
}
