package com.grocery.microservices.cart.dto;

import java.util.List;

public class CartDTO {
    private Long id;
    private String status;
    private String currency;
    private List<CartItemDTO> items;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public List<CartItemDTO> getItems() { return items; }
    public void setItems(List<CartItemDTO> items) { this.items = items; }
}
