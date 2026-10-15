package com.grocery.microservices.ledger.service;

import com.grocery.microservices.ledger.exception.LedgerNotFoundException;
import com.grocery.microservices.ledger.model.Ledger;
import com.grocery.microservices.ledger.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@ActiveProfiles("test")
class LedgerServiceTest {

    @Mock
    private LedgerRepository ledgerRepository;

    @InjectMocks
    private LedgerService ledgerService;

    private Ledger testSummary;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        testSummary = new Ledger();
        testSummary.setId(1L);
        testSummary.setUserId("customer-1");
        testSummary.setOrderId(1L);
        testSummary.setTotalAmount(BigDecimal.valueOf(99.99));
        testSummary.setItemCount(3);
        testSummary.setCreatedAt(LocalDateTime.now());
    }

    @Test
    void testGetSummariesByCustomer() {
        when(ledgerRepository.findByUserIdOrderByCreatedAtDesc("customer-1"))
                .thenReturn(Arrays.asList(testSummary));

        List<Ledger> results = ledgerService.getSummariesByCustomer("customer-1");

        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("customer-1", results.get(0).getUserId());
        verify(ledgerRepository, times(1)).findByUserIdOrderByCreatedAtDesc("customer-1");
    }

    @Test
    void testGetSummariesByCustomerNotFound() {
        when(ledgerRepository.findByUserIdOrderByCreatedAtDesc("nonexistent"))
                .thenReturn(Arrays.asList());

        List<Ledger> results = ledgerService.getSummariesByCustomer("nonexistent");

        assertNotNull(results);
        assertTrue(results.isEmpty());
        verify(ledgerRepository, times(1)).findByUserIdOrderByCreatedAtDesc("nonexistent");
    }

    @Test
    void testGetSummaryByOrder() {
        when(ledgerRepository.findByOrderIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testSummary));

        Ledger result = ledgerService.getSummaryByOrder("customer-1", 1L);

        assertEquals(testSummary, result);
        verify(ledgerRepository).findByOrderIdAndUserId(1L, "customer-1");
    }

    @Test
    void testGetSummaryByOrderIsNotFoundForAnotherCustomer() {
        when(ledgerRepository.findByOrderIdAndUserId(1L, "customer-2")).thenReturn(Optional.empty());

        assertThrows(LedgerNotFoundException.class, () -> ledgerService.getSummaryByOrder("customer-2", 1L));
        verify(ledgerRepository).findByOrderIdAndUserId(1L, "customer-2");
    }

    @Test
    void testGetTotalSpending() {
        Ledger summary1 = new Ledger();
        summary1.setUserId("customer-1");
        summary1.setTotalAmount(BigDecimal.valueOf(100.00));

        Ledger summary2 = new Ledger();
        summary2.setUserId("customer-1");
        summary2.setTotalAmount(BigDecimal.valueOf(150.00));

        when(ledgerRepository.findByUserIdOrderByCreatedAtDesc("customer-1"))
                .thenReturn(Arrays.asList(summary1, summary2));

        BigDecimal totalSpending = ledgerService.getTotalSpending("customer-1");

        assertEquals(BigDecimal.valueOf(250.00), totalSpending);
    }

    @Test
    void testGetTotalSpendingNoOrders() {
        when(ledgerRepository.findByUserIdOrderByCreatedAtDesc("customer-1"))
                .thenReturn(Arrays.asList());

        assertEquals(BigDecimal.ZERO, ledgerService.getTotalSpending("customer-1"));
    }

    @Test
    void testGetOrderCount() {
        when(ledgerRepository.countByUserId("customer-1")).thenReturn(5L);

        assertEquals(5L, ledgerService.getOrderCount("customer-1"));
        verify(ledgerRepository, times(1)).countByUserId("customer-1");
    }

    @Test
    void testGetAverageOrderAmount() {
        Ledger summary1 = new Ledger();
        summary1.setTotalAmount(BigDecimal.valueOf(100.00));
        Ledger summary2 = new Ledger();
        summary2.setTotalAmount(BigDecimal.valueOf(200.00));

        when(ledgerRepository.findByUserIdOrderByCreatedAtDesc("customer-1"))
                .thenReturn(Arrays.asList(summary1, summary2));

        BigDecimal averageAmount = ledgerService.getAverageOrderAmount("customer-1");

        assertEquals(0, BigDecimal.valueOf(150.00).compareTo(averageAmount));
    }

    @Test
    void testGetAverageOrderAmountNoOrders() {
        when(ledgerRepository.findByUserIdOrderByCreatedAtDesc("customer-1"))
                .thenReturn(Arrays.asList());

        assertEquals(BigDecimal.ZERO, ledgerService.getAverageOrderAmount("customer-1"));
    }

    @Test
    void testGetFormattedReceiptScopedToCustomer() {
        when(ledgerRepository.findByOrderIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testSummary));

        String receipt = ledgerService.getFormattedReceipt("customer-1", 1L);

        assertTrue(receipt.contains("Order ID: 1"));
        assertTrue(receipt.contains("Total: 99.99 SEK"));
    }
}
