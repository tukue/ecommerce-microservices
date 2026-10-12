package com.grocery.microservices.ledger.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(indexes = {
        @Index(name = "idx_ledger_user_id", columnList = "user_id"),
        @Index(name = "idx_summary_order_id", columnList = "order_id")
})
public class Ledger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id")
    private String userId;
    @Column(name = "order_id", unique = true, nullable = false)
    private Long orderId;
    @Column(name = "total_amount")
    private BigDecimal totalAmount;
    @Column(nullable = false, length = 3)
    private String currency = "SEK";
    @Column(name = "item_count")
    private Integer itemCount;
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    private String details;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Integer getItemCount() { return itemCount; }
    public void setItemCount(Integer itemCount) { this.itemCount = itemCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
}
