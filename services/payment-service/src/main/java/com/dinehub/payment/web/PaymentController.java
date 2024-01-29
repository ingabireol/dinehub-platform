package com.dinehub.payment.web;

import com.dinehub.common.security.Roles;
import com.dinehub.payment.dto.PaymentDtos;
import com.dinehub.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Read-only.
 *
 * <p>Payment is triggered by an event, never by an HTTP call. Exposing a
 * "charge this order" endpoint would make double-charging a matter of someone
 * clicking twice.
 */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(name = "Payments", description = "Payment records. Charging happens on order.placed.")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "The payment recorded for an order")
    public PaymentDtos.PaymentResponse getByOrder(@PathVariable UUID orderId) {
        return paymentService.getByOrder(orderId);
    }

    @GetMapping("/mine")
    @PreAuthorize(Roles.HAS_CUSTOMER)
    @Operation(summary = "The authenticated customer's payment history")
    public List<PaymentDtos.PaymentResponse> myPayments(@AuthenticationPrincipal UUID customerId) {
        return paymentService.listForCustomer(customerId);
    }
}
