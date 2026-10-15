package com.grocery.microservices.cart.exception;

public class MixedCurrencyCartException extends RuntimeException {
    public MixedCurrencyCartException() {
        super("A cart cannot contain products in different currencies");
    }
}
