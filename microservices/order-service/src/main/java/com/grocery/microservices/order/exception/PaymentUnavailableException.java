package com.grocery.microservices.order.exception;

public class PaymentUnavailableException extends RuntimeException {
    public PaymentUnavailableException() {
        super("Payment processing is temporarily unavailable");
    }

    public PaymentUnavailableException(Throwable cause) {
        super("Payment processing is temporarily unavailable", cause);
    }
}
