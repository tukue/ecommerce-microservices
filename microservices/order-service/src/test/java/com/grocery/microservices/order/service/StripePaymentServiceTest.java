package com.grocery.microservices.order.service;

import com.grocery.microservices.order.config.AuthenticatedCustomer;
import com.grocery.microservices.order.exception.OrderNotFoundException;
import com.grocery.microservices.order.model.Order;
import com.grocery.microservices.order.model.PaymentAttempt;
import com.grocery.microservices.order.model.PaymentAttemptStatus;
import com.grocery.microservices.order.repository.OrderRepository;
import com.grocery.microservices.order.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StripePaymentServiceTest {
    private OrderService orderService;
    private OrderRepository orderRepository;
    private PaymentAttemptRepository paymentAttemptRepository;
    private StripePaymentService paymentService;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        orderRepository = mock(OrderRepository.class);
        paymentAttemptRepository = mock(PaymentAttemptRepository.class);
        paymentService = new StripePaymentService(orderService, orderRepository, paymentAttemptRepository,
                "", "", "http://localhost:5173");
    }

    @Test
    void customerCannotStartPaymentForAnotherCustomersOrder() {
        Order order = pendingOrder("another-customer");
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(order));

        assertThrows(OrderNotFoundException.class, () ->
                paymentService.createCheckoutSession(10L, new AuthenticatedCustomer("customer-1")));
        verify(paymentAttemptRepository, never()).findFirstByOrderIdOrderByAttemptNumberDesc(10L);
    }

    @Test
    void reusesExistingOpenStripeSessionForCheckoutRetry() {
        when(orderRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(pendingOrder("customer-1")));
        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setOrderId(10L);
        attempt.setAttemptNumber(1);
        attempt.setStripeSessionId("cs_test_existing");
        attempt.setCheckoutUrl("https://checkout.stripe.com/session");
        attempt.setStatus(PaymentAttemptStatus.OPEN);
        when(paymentAttemptRepository.findFirstByOrderIdOrderByAttemptNumberDesc(10L))
                .thenReturn(Optional.of(attempt));

        var response = paymentService.createCheckoutSession(10L, new AuthenticatedCustomer("customer-1"));

        assertEquals("https://checkout.stripe.com/session", response.checkoutUrl());
        verify(paymentAttemptRepository, never()).save(attempt);
    }

    private Order pendingOrder(String userId) {
        Order order = new Order();
        order.setId(10L);
        order.setUserId(userId);
        order.setStatus(com.grocery.microservices.order.model.OrderStatus.PENDING);
        order.setCurrency("SEK");
        return order;
    }
}
