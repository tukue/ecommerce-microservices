package com.grocery.microservices.ledger.service;

import com.grocery.microservices.ledger.model.Ledger;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LedgerProcessorTest {

    private final LedgerProcessor processor = new LedgerProcessor();

    @Test
    void shouldCalculateTotalSpending() {
        Ledger l1 = new Ledger();
        l1.setTotalAmount(new BigDecimal("10.50"));
        Ledger l2 = new Ledger();
        l2.setTotalAmount(new BigDecimal("20.00"));

        List<Ledger> ledgers = Arrays.asList(l1, l2);

        assertEquals(new BigDecimal("30.50"), processor.calculateTotalSpending(ledgers));
    }

    @Test
    void shouldCalculateAverageOrderAmount() {
        Ledger l1 = new Ledger();
        l1.setTotalAmount(new BigDecimal("10.00"));
        Ledger l2 = new Ledger();
        l2.setTotalAmount(new BigDecimal("20.00"));

        List<Ledger> ledgers = Arrays.asList(l1, l2);

        assertEquals(new BigDecimal("15.00"), processor.calculateAverageOrderAmount(ledgers));
    }

    @Test
    void shouldFormatReceipt() {
        Ledger ledger = new Ledger();
        ledger.setOrderId(123L);
        ledger.setCreatedAt(LocalDateTime.of(2023, 10, 27, 10, 0));
        ledger.setItemCount(3);
        ledger.setTotalAmount(new BigDecimal("45.67"));
        ledger.setDetails("Apple, Banana, Carrot");

        String receipt = processor.formatReceipt(ledger);

        assertTrue(receipt.contains("Order ID: 123"));
        assertTrue(receipt.contains("Items: 3"));
        assertTrue(receipt.contains("Total: 45.67 SEK"));
        assertTrue(receipt.contains("Details: Apple, Banana, Carrot"));
    }
}
