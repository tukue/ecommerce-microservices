package com.grocery.microservices.order.controller;

import com.grocery.microservices.order.config.AuthenticatedCustomer;
import com.grocery.microservices.order.dto.PaymentSessionDTO;
import com.grocery.microservices.order.service.StripePaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/orders")
public class PaymentController {
    private final StripePaymentService stripePaymentService;

    public PaymentController(StripePaymentService stripePaymentService) {
        this.stripePaymentService = stripePaymentService;
    }

    @PostMapping("/{orderId}/payment-session")
    @PreAuthorize("hasAuthority('SCOPE_order:write')")
    public ResponseEntity<PaymentSessionDTO> createCheckoutSession(
            @PathVariable Long orderId,
            @AuthenticationPrincipal AuthenticatedCustomer customer) {
        return ResponseEntity.ok(stripePaymentService.createCheckoutSession(orderId, customer));
    }
}
