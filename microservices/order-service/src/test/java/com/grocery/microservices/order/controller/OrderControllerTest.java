package com.grocery.microservices.order.controller;

import com.grocery.microservices.order.config.AuthenticatedCustomer;
import com.grocery.microservices.order.config.TestJwtSupport;
import com.grocery.microservices.order.config.TestSecurityConfig;
import com.grocery.microservices.order.dto.CheckoutRequest;
import com.grocery.microservices.order.exception.InvalidOrderStateException;
import com.grocery.microservices.order.exception.OrderNotFoundException;
import com.grocery.microservices.order.model.Order;
import com.grocery.microservices.order.model.OrderStatus;
import com.grocery.microservices.order.service.OrderService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(OrderController.class)
@Import(TestSecurityConfig.class)
public class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Autowired
    private ObjectMapper objectMapper;

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    public void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/customer/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void rejectsRequestWhenTokenIsMissingScope() throws Exception {
        mockMvc.perform(get("/api/customer/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.tokenWithScopes("customer-1", "cart:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    public void checkoutCreatesOrderFromCart() throws Exception {
        CheckoutRequest checkoutRequest = new CheckoutRequest();
        checkoutRequest.setCartId(1L);

        Order savedOrder = new Order();
        savedOrder.setId(1L);
        savedOrder.setStatus(OrderStatus.PENDING);
        savedOrder.setTotal(24.50);

        when(orderService.checkout(anyLong(), any(), any(), any(AuthenticatedCustomer.class), any(String.class)))
                .thenReturn(savedOrder);

        mockMvc.perform(post("/api/customer/checkout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkoutRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/customer/orders/1"))
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.total").value(24.50));
    }

    @Test
    public void checkoutForwardsIdempotencyKeyHeader() throws Exception {
        CheckoutRequest checkoutRequest = new CheckoutRequest();
        checkoutRequest.setCartId(1L);

        Order savedOrder = new Order();
        savedOrder.setId(1L);
        savedOrder.setStatus(OrderStatus.PENDING);
        savedOrder.setTotal(24.50);

        when(orderService.checkout(eq(1L), eq("checkout-key-123"), any(), any(AuthenticatedCustomer.class),
                any(String.class))).thenReturn(savedOrder);

        mockMvc.perform(post("/api/customer/checkout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .header("Idempotency-Key", "checkout-key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkoutRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    public void rejectsInvalidCheckoutPayloadBeforeCreatingOrder() throws Exception {
        CheckoutRequest checkoutRequest = new CheckoutRequest();
        checkoutRequest.setCartId(0L);

        mockMvc.perform(post("/api/customer/checkout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(checkoutRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation Failed"))
                .andExpect(jsonPath("$.validationErrors.cartId").value("Cart ID must be positive"));

        verifyNoInteractions(orderService);
    }

    @Test
    public void testUpdateStatus() throws Exception {
        Order updatedOrder = new Order();
        updatedOrder.setId(1L);
        updatedOrder.setStatus(OrderStatus.COMPLETED);

        when(orderService.updateOrderStatus(anyLong(), any(OrderStatus.class), any(AuthenticatedCustomer.class)))
                .thenReturn(updatedOrder);

        mockMvc.perform(patch("/api/customer/orders/1/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .param("status", "COMPLETED")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    public void rejectsInvalidOrderStatusTransition() throws Exception {
        when(orderService.updateOrderStatus(anyLong(), any(OrderStatus.class), any(AuthenticatedCustomer.class)))
                .thenThrow(new InvalidOrderStateException("Order status changes are managed by the payment lifecycle"));

        mockMvc.perform(patch("/api/customer/orders/1/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .param("status", "PENDING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Order status changes are managed by the payment lifecycle"));
    }

    @Test
    public void returnsNotFoundWhenOtherCustomersOrderIsRequested() throws Exception {
        when(orderService.getOrder(anyLong(), any(AuthenticatedCustomer.class)))
                .thenThrow(new OrderNotFoundException(99L));

        mockMvc.perform(get("/api/customer/orders/99")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-2"))))
                .andExpect(status().isNotFound());
    }
}