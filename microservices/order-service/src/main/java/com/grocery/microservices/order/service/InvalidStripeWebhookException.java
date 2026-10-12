package com.grocery.microservices.order.service;

public class InvalidStripeWebhookException extends RuntimeException {
    public InvalidStripeWebhookException() {
        super("Invalid Stripe webhook event");
    }
}
