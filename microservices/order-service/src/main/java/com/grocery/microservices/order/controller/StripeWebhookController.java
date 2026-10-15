package com.grocery.microservices.order.controller;

import com.grocery.microservices.order.service.StripePaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/webhooks")
public class StripeWebhookController {
    private final StripePaymentService stripePaymentService;

    public StripeWebhookController(StripePaymentService stripePaymentService) {
        this.stripePaymentService = stripePaymentService;
    }

    @PostMapping("/stripe")
    public ResponseEntity<Void> receiveStripeEvent(
            @RequestBody String payload,
            @RequestHeader(name = "Stripe-Signature") String signature) {
        stripePaymentService.handleWebhook(payload, signature);
        return ResponseEntity.noContent().build();
    }
}
