package com.grocery.microservices.ledger.service;

import com.grocery.microservices.ledger.model.Ledger;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure domain logic for ledger calculations.
 * Independent of Spring and Database.
 */
public class LedgerProcessor {

    public BigDecimal calculateTotalSpending(List<Ledger> ledgers) {
        return ledgers.stream()
                .map(l -> l.getTotalAmount() != null ? l.getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal calculateAverageOrderAmount(List<Ledger> ledgers) {
        if (ledgers == null || ledgers.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = calculateTotalSpending(ledgers);
        return total.divide(BigDecimal.valueOf(ledgers.size()), 2, RoundingMode.HALF_UP);
    }

    public String formatReceipt(Ledger ledger) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- RECEIPT ---\n");
        sb.append("Order ID: ").append(ledger.getOrderId()).append("\n");
        sb.append("Date: ").append(ledger.getCreatedAt()).append("\n");
        sb.append("Items: ").append(ledger.getItemCount()).append("\n");
        sb.append("Total: ").append(ledger.getTotalAmount()).append(" ")
                .append(ledger.getCurrency()).append("\n");
        if (ledger.getDetails() != null) {
            sb.append("Details: ").append(ledger.getDetails()).append("\n");
        }
        sb.append("---------------\n");
        return sb.toString();
    }
}
