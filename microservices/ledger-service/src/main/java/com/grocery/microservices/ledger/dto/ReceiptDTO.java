package com.grocery.microservices.ledger.dto;

public record ReceiptDTO(String status, String content) {
    public static ReceiptDTO pending() {
        return new ReceiptDTO("pending", null);
    }

    public static ReceiptDTO ready(String content) {
        return new ReceiptDTO("ready", content);
    }
}
