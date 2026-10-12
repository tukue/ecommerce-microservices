package com.grocery.microservices.cart.client;

public record CatalogProduct(Long id, String name, double price, String currency,
                             Boolean available, int stockQuantity) {
    public CatalogProduct(Long id, String name, double price, Boolean available, int stockQuantity) {
        this(id, name, price, "SEK", available, stockQuantity);
    }
}
