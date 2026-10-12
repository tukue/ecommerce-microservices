package com.grocery.microservices.order.service;

import com.grocery.microservices.order.model.Order;
import com.grocery.microservices.order.model.OrderStatus;
import com.grocery.microservices.order.config.AuthenticatedCustomer;
import com.grocery.microservices.order.client.CartClient;
import com.grocery.microservices.order.client.CartItemSnapshot;
import com.grocery.microservices.order.client.CartSnapshot;
import com.grocery.microservices.order.client.ProductClient;
import com.grocery.microservices.order.client.StockReservationSnapshot;
import com.grocery.microservices.order.exception.EmptyCartException;
import com.grocery.microservices.order.exception.CartServiceUnavailableException;
import com.grocery.microservices.order.exception.CheckoutCartAlreadyCheckedOutException;
import com.grocery.microservices.order.exception.CheckoutCartNotFoundException;
import com.grocery.microservices.order.exception.InsufficientProductStockException;
import com.grocery.microservices.order.exception.InvalidOrderStateException;
import com.grocery.microservices.order.exception.OrderNotFoundException;
import com.grocery.microservices.order.exception.ProductUnavailableException;
import com.grocery.microservices.order.exception.ProductServiceUnavailableException;
import com.grocery.microservices.order.repository.OrderRepository;
import com.grocery.microservices.order.event.OrderCreatedEvent;
import com.grocery.microservices.order.eventstore.OrderEventStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class OrderServiceTest {
    private OrderRepository orderRepository;
    private CartClient cartClient;
    private ProductClient productClient;
    private OrderEventStore orderEventStore;
    private OrderService orderService;
    private Order testOrder;
    private AuthenticatedCustomer customer1;
    private AuthenticatedCustomer customer2;

    @BeforeEach
    void setUp() {
        orderRepository = Mockito.mock(OrderRepository.class);
        cartClient = Mockito.mock(CartClient.class);
        productClient = Mockito.mock(ProductClient.class);
        orderEventStore = Mockito.mock(OrderEventStore.class);
        orderService = new OrderService(orderRepository, cartClient, productClient, orderEventStore,
                new SimpleMeterRegistry());
        customer1 = new AuthenticatedCustomer("customer-1");
        customer2 = new AuthenticatedCustomer("customer-2");
        testOrder = new Order();
        testOrder.setId(1L);
        testOrder.setUserId("customer-1");
        testOrder.setTotal(100.0);
        testOrder.setStatus(OrderStatus.PENDING);
    }

    @Test
    void testCreateOrder() {
        Order newOrder = new Order();
        newOrder.setTotal(50.0);
        when(orderRepository.save(Mockito.any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order createdOrder = orderService.createOrder(newOrder);

        assertNotNull(createdOrder);
        assertEquals(OrderStatus.PENDING, createdOrder.getStatus());
        assertNotNull(createdOrder.getOrderDate());
        assertNotNull(createdOrder.getIdempotencyKey());
        verify(orderRepository, times(1)).save(Mockito.any(Order.class));
        verify(orderEventStore, never()).enqueue(Mockito.any(OrderCreatedEvent.class));
    }

    @Test
    void customerCannotMarkOrderPaid() {
        when(orderRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testOrder));

        assertThrows(InvalidOrderStateException.class,
                () -> orderService.updateOrderStatus(1L, OrderStatus.COMPLETED, customer1));
        verify(orderEventStore, never()).enqueue(Mockito.any(OrderCreatedEvent.class));
    }

    @Test
    void verifiedPaymentCompletesOrderAndEnqueuesReceiptEvent() {
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(Mockito.any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order paidOrder = orderService.completePayment(1L);

        assertEquals(OrderStatus.COMPLETED, paidOrder.getStatus());
        verify(orderEventStore).enqueue(Mockito.any(OrderCreatedEvent.class));
    }

    @Test
    void paymentExpiryReleasesReservationsAndReopensCart() {
        testOrder.setCartId(5L);
        testOrder.setIdempotencyKey("checkout-key");
        testOrder.setOrderLines(List.of(new OrderLine(101L, "Apples", 2.5, 2)));
        when(orderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(testOrder));
        when(orderRepository.save(Mockito.any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order cancelledOrder = orderService.expirePayment(1L);

        assertEquals(OrderStatus.CANCELLED, cancelledOrder.getStatus());
        verify(productClient).releaseAfterPaymentExpiry("checkout:customer-1:checkout-key:101");
        verify(cartClient).markOpen(5L, null);
        verify(orderEventStore, never()).enqueue(Mockito.any(OrderCreatedEvent.class));
    }

    @Test
    void testUpdateOrderStatus_InvalidFromCompleted() {
        testOrder.setStatus(OrderStatus.COMPLETED);
        when(orderRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testOrder));

        assertThrows(InvalidOrderStateException.class, () ->
            orderService.updateOrderStatus(1L, OrderStatus.CANCELLED, customer1)
        );
    }

    @Test
    void checkoutCreatesOrderFromCartSnapshot() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 2),
                new CartItemSnapshot(11L, 102L, "Bread", 3.00, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        stubReserve(101L, 2);
        stubReserve(102L, 1);
        when(orderRepository.save(Mockito.any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order createdOrder = orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token");

        assertEquals("customer-1", createdOrder.getUserId());
        assertEquals("idem-key-1", createdOrder.getIdempotencyKey());
        assertEquals(8.0, createdOrder.getTotal());
        assertEquals(2, createdOrder.getOrderLines().size());
        assertEquals(5.0, createdOrder.getOrderLines().getFirst().getLineTotal());
        assertEquals(OrderStatus.PENDING, createdOrder.getStatus());
        verify(productClient).reserve(eq(101L), eq(2), anyString(), eq("Bearer token"));
        verify(productClient).reserve(eq(102L), eq(1), anyString(), eq("Bearer token"));
        verify(cartClient).markCheckedOut(1L, "Bearer token");
        verify(orderRepository).save(createdOrder);
        InOrder inOrder = inOrder(cartClient, orderRepository);
        inOrder.verify(cartClient).markCheckedOut(1L, "Bearer token");
        inOrder.verify(orderRepository).save(createdOrder);
    }

    @Test
    void checkoutGeneratesIdempotencyKeyWhenNotSupplied() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        stubReserve(101L, 1);
        when(orderRepository.save(Mockito.any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Order createdOrder = orderService.checkout(1L, null, null, customer1, "Bearer token");

        assertNotNull(createdOrder.getIdempotencyKey());
    }

    @Test
    void checkoutReturnsOriginalOrderForRepeatedIdempotencyKey() {
        testOrder.setIdempotencyKey("idem-key-1");
        when(orderRepository.findFirstByUserIdAndIdempotencyKey("customer-1", "idem-key-1"))
                .thenReturn(Optional.of(testOrder));

        Order replayedOrder = orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token");

        assertEquals(testOrder.getId(), replayedOrder.getId());
        verify(orderRepository, never()).save(Mockito.any(Order.class));
        verify(orderEventStore, never()).enqueue(Mockito.any(OrderCreatedEvent.class));
        verify(cartClient, never()).getCart(Mockito.anyLong(), Mockito.anyString());
        verify(cartClient, never()).markCheckedOut(Mockito.anyLong(), Mockito.anyString());
        verify(productClient, never()).reserve(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void checkoutRejectsEmptyCartWithoutPersistingOrder() {
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(new CartSnapshot(1L, List.of()));

        assertThrows(EmptyCartException.class, () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));

        verify(orderRepository, never()).save(Mockito.any(Order.class));
        verify(productClient, never()).reserve(anyLong(), anyInt(), anyString(), anyString());
    }

    @Test
    void checkoutRejectsAlreadyCheckedOutCartBeforeReservingStock() {
        CartSnapshot cart = new CartSnapshot(1L, "CHECKED_OUT", List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);

        assertThrows(CheckoutCartAlreadyCheckedOutException.class,
                () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));

        verify(productClient, never()).reserve(anyLong(), anyInt(), anyString(), anyString());
        verify(orderRepository, never()).save(Mockito.any(Order.class));
        verify(cartClient, never()).markCheckedOut(anyLong(), anyString());
    }

    @Test
    void checkoutDoesNotPersistWhenProductIsUnavailable() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        when(productClient.reserve(eq(101L), eq(1), anyString(), anyString()))
                .thenThrow(new ProductUnavailableException(101L));

        assertThrows(ProductUnavailableException.class,
                () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));

        verify(orderRepository, never()).save(Mockito.any(Order.class));
    }

    @Test
    void checkoutDoesNotPersistWhenStockIsInsufficient() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 5)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        when(productClient.reserve(eq(101L), eq(5), anyString(), anyString()))
                .thenThrow(new InsufficientProductStockException(101L, 5, 2));

        assertThrows(InsufficientProductStockException.class,
                () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));

        verify(orderRepository, never()).save(Mockito.any(Order.class));
    }

    @Test
    void checkoutReleasesAlreadyReservedLinesWhenLaterLineFails() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 2),
                new CartItemSnapshot(11L, 102L, "Bread", 3.00, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        stubReserve(101L, 2);
        when(productClient.reserve(eq(102L), eq(1), anyString(), anyString()))
                .thenThrow(new InsufficientProductStockException(102L, 1, 0));

        assertThrows(InsufficientProductStockException.class,
                () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));

        verify(productClient).release(contains("101"), eq("Bearer token"));
        verify(productClient, never()).release(contains("102"), anyString());
        verify(orderRepository, never()).save(Mockito.any(Order.class));
    }

    @Test
    void checkoutReleasesReservationsWhenOrderPersistenceFails() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        stubReserve(101L, 1);
        when(orderRepository.save(Mockito.any(Order.class))).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class,
                () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));

        verify(cartClient).markCheckedOut(1L, "Bearer token");
        verify(cartClient).markOpen(1L, "Bearer token");
        verify(productClient).release(contains("101"), eq("Bearer token"));
    }

    @Test
    void checkoutReleasesReservationsWhenCartCheckoutMarkerFails() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        stubReserve(101L, 1);
        org.mockito.Mockito.doThrow(new CartServiceUnavailableException()).when(cartClient)
                .markCheckedOut(1L, "Bearer token");

        assertThrows(CartServiceUnavailableException.class,
                () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));

        verify(orderRepository, never()).save(Mockito.any(Order.class));
        verify(cartClient, never()).markOpen(anyLong(), anyString());
        verify(productClient).release(contains("101"), eq("Bearer token"));
    }

    @Test
    void checkoutDoesNotCreateDuplicateOrderWhenCartClaimedByConcurrentRequest() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        stubReserve(101L, 1);
        org.mockito.Mockito.doThrow(new CheckoutCartAlreadyCheckedOutException(1L)).when(cartClient)
                .markCheckedOut(1L, "Bearer token");

        assertThrows(CheckoutCartAlreadyCheckedOutException.class,
                () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));

        verify(orderRepository, never()).save(Mockito.any(Order.class));
        verify(cartClient, never()).markOpen(anyLong(), anyString());
        verify(productClient).release(contains("101"), eq("Bearer token"));
    }

    @Test
    void checkoutRevertFailureDoesNotMaskOriginError() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        stubReserve(101L, 1);
        when(orderRepository.save(Mockito.any(Order.class))).thenThrow(new IllegalStateException("db down"));
        org.mockito.Mockito.doThrow(new CartServiceUnavailableException()).when(cartClient)
                .markOpen(1L, "Bearer token");

        assertThrows(IllegalStateException.class,
                () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));

        verify(cartClient).markCheckedOut(1L, "Bearer token");
        verify(cartClient).markOpen(1L, "Bearer token");
        verify(productClient).release(contains("101"), eq("Bearer token"));
    }

    @Test
    void checkoutReplaysExistingOrderWhenIdempotencyKeyRaces() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        stubReserve(101L, 1);
        testOrder.setIdempotencyKey("idem-key-1");
        when(orderRepository.findFirstByUserIdAndIdempotencyKey("customer-1", "idem-key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(testOrder));
        when(orderRepository.save(Mockito.any(Order.class)))
                .thenThrow(new DataIntegrityViolationException("uk_orders_user_idempotency"));

        Order replayedOrder = orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token");

        assertEquals(1L, replayedOrder.getId());
        verify(cartClient).markCheckedOut(1L, "Bearer token");
        verify(cartClient, never()).markOpen(anyLong(), anyString());
        verify(productClient, never()).release(anyString(), anyString());
    }

    @Test
    void checkoutReleaseFailureDoesNotMaskOriginError() {
        CartSnapshot cart = new CartSnapshot(1L, List.of(
                new CartItemSnapshot(10L, 101L, "Apples", 2.50, 1)));
        when(cartClient.getCart(1L, "Bearer token")).thenReturn(cart);
        stubReserve(101L, 1);
        when(orderRepository.save(Mockito.any(Order.class))).thenThrow(new IllegalStateException("db down"));
        org.mockito.Mockito.doThrow(new ProductServiceUnavailableException()).when(productClient)
                .release(anyString(), anyString());

        assertThrows(IllegalStateException.class,
                () -> orderService.checkout(1L, "idem-key-1", null, customer1, "Bearer token"));
    }

    @Test
    void checkoutDoesNotPersistWhenCartIsMissing() {
        when(cartClient.getCart(99L, "Bearer token")).thenThrow(new CheckoutCartNotFoundException(99L));

        assertThrows(CheckoutCartNotFoundException.class,
                () -> orderService.checkout(99L, "idem-key-1", null, customer1, "Bearer token"));

        verify(orderRepository, never()).save(Mockito.any(Order.class));
    }

    private void stubReserve(Long productId, int quantity) {
        when(productClient.reserve(eq(productId), eq(quantity), anyString(), anyString()))
                .thenReturn(new StockReservationSnapshot("checkout:key:" + productId, productId, quantity, "ACTIVE"));
    }

    @Test
    void testUpdateOrderStatus_InvalidPendingToPending() {
        when(orderRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testOrder));

        InvalidOrderStateException exception = assertThrows(InvalidOrderStateException.class, () ->
            orderService.updateOrderStatus(1L, OrderStatus.PENDING, customer1)
        );

        assertEquals("Order status changes are managed by the payment lifecycle", exception.getMessage());
        verify(orderRepository, never()).save(Mockito.any(Order.class));
    }

    @Test
    void testGetOrderById() {
        when(orderRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testOrder));

        Order foundOrder = orderService.getOrder(1L, customer1);
        assertNotNull(foundOrder);
        assertEquals(1L, foundOrder.getId());

        when(orderRepository.findByIdAndUserId(2L, "customer-1")).thenReturn(Optional.empty());
        assertThrows(OrderNotFoundException.class, () -> orderService.getOrder(2L, customer1));
    }

    @Test
    void getOwnedOrderIsNotFoundForAnotherCustomer() {
        when(orderRepository.findByIdAndUserId(1L, "customer-2")).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class, () -> orderService.getOrder(1L, customer2));
    }

    @Test
    void updateOrderStatusIsNotFoundForAnotherCustomer() {
        when(orderRepository.findByIdAndUserId(1L, "customer-2")).thenReturn(Optional.empty());

        assertThrows(OrderNotFoundException.class,
                () -> orderService.updateOrderStatus(1L, OrderStatus.COMPLETED, customer2));

        verify(orderRepository, never()).save(Mockito.any(Order.class));
    }
}
