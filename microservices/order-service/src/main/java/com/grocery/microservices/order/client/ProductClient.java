package com.grocery.microservices.order.client;

public interface ProductClient {
    ProductSnapshot getProduct(Long productId);

    StockReservationSnapshot reserve(Long productId, int quantity, String reservationKey, String authorizationHeader);

    void release(String reservationKey, String authorizationHeader);

    void releaseAfterPaymentExpiry(String reservationKey);
}