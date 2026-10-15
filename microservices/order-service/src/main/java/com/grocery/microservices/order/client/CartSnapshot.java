package com.grocery.microservices.order.client;

import java.util.List;

public record CartSnapshot(Long id, String status, String currency, List<CartItemSnapshot> items) {
    public CartSnapshot(Long id, List<CartItemSnapshot> items) {
        this(id, "OPEN", "SEK", items);
    }

    public CartSnapshot(Long id, String status, List<CartItemSnapshot> items) {
        this(id, status, "SEK", items);
    }

    public boolean isOpen() {
        return status == null || "OPEN".equals(status);
    }
}
