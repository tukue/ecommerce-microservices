package com.grocery.microservices.ledger.controller;

import com.grocery.microservices.ledger.config.TestJwtSupport;
import com.grocery.microservices.ledger.config.TestSecurityConfig;
import com.grocery.microservices.ledger.exception.LedgerNotFoundException;
import com.grocery.microservices.ledger.model.Ledger;
import com.grocery.microservices.ledger.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(LedgerController.class)
@Import(TestSecurityConfig.class)
public class LedgerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LedgerService ledgerService;

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    public void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/customer/ledger"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void rejectsRequestWhenTokenIsMissingScope() throws Exception {
        mockMvc.perform(get("/api/customer/ledger")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.tokenWithScopes("customer-1", "cart:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    public void returnsAggregatedSummaryForAuthenticatedCustomer() throws Exception {
        Ledger ledger = new Ledger();
        ledger.setId(1L);
        ledger.setOrderId(42L);
        ledger.setUserId("customer-1");
        ledger.setItemCount(2);
        ledger.setDetails("Apple, Banana");
        ledger.setTotalAmount(new BigDecimal("12.5"));
        ledger.setCurrency("SEK");
        ledger.setCreatedAt(LocalDateTime.now());

        when(ledgerService.getOrderCount("customer-1")).thenReturn(1L);
        when(ledgerService.getTotalSpending("customer-1")).thenReturn(new BigDecimal("12.5"));
        when(ledgerService.getAverageOrderAmount("customer-1")).thenReturn(new BigDecimal("12.5"));
        when(ledgerService.getSummariesByCustomer("customer-1")).thenReturn(java.util.List.of(ledger));

        mockMvc.perform(get("/api/customer/ledger")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("customer-1"))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.totalSpending").value(12.5))
                .andExpect(jsonPath("$.averageOrderAmount").value(12.5))
                .andExpect(jsonPath("$.recentOrders[0].orderId").value(42L))
                .andExpect(jsonPath("$.recentOrders[0].currency").value("SEK"))
                .andExpect(jsonPath("$.recentOrders[0].items[0]").value("Apple"));
    }

    @Test
    public void returnsEmptySummaryWhenCustomerHasNoOrders() throws Exception {
        when(ledgerService.getOrderCount("customer-1")).thenReturn(0L);
        when(ledgerService.getTotalSpending("customer-1")).thenReturn(BigDecimal.ZERO);
        when(ledgerService.getAverageOrderAmount("customer-1")).thenReturn(BigDecimal.ZERO);
        when(ledgerService.getSummariesByCustomer("customer-1")).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/customer/ledger")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(0))
                .andExpect(jsonPath("$.recentOrders").isEmpty());
    }

    @Test
    public void returnsPendingWhenReceiptHasNotBeenProjectedYet() throws Exception {
        when(ledgerService.getFormattedReceipt("customer-2", 99L))
                .thenThrow(new LedgerNotFoundException(99L));

        mockMvc.perform(get("/api/customer/ledger/orders/99/receipt")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-2"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    public void returnsReceiptForOwnedOrder() throws Exception {
        when(ledgerService.getFormattedReceipt("customer-1", 42L))
                .thenReturn("--- RECEIPT ---\nOrder ID: 42\n---------------\n");

        mockMvc.perform(get("/api/customer/ledger/orders/42/receipt")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.content").value("--- RECEIPT ---\nOrder ID: 42\n---------------\n"));
    }
}
