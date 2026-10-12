package com.grocery.microservices.order.service;

import com.grocery.microservices.order.model.Order;
import com.grocery.microservices.order.model.OrderLine;
import com.grocery.microservices.order.model.OrderStatus;
import com.grocery.microservices.order.config.AuthenticatedCustomer;
import com.grocery.microservices.order.client.CartClient;
import com.grocery.microservices.order.client.CartItemSnapshot;
import com.grocery.microservices.order.client.CartSnapshot;
import com.grocery.microservices.order.client.ProductClient;
import com.grocery.microservices.order.exception.EmptyCartException;
import com.grocery.microservices.order.exception.CheckoutCartAlreadyCheckedOutException;
import com.grocery.microservices.order.exception.InvalidOrderStateException;
import com.grocery.microservices.order.exception.OrderNotFoundException;
import com.grocery.microservices.order.repository.OrderRepository;
import com.grocery.microservices.order.event.OrderCreatedEvent;
import com.grocery.microservices.order.eventstore.OrderEventStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final OrderRepository repo;
    private final CartClient cartClient;
    private final ProductClient productClient;
    private final OrderEventStore orderEventStore;
    private final Counter reservationsReserved;
    private final Counter reservationsReleased;

    public OrderService(OrderRepository repo, CartClient cartClient, ProductClient productClient,
                        OrderEventStore orderEventStore, MeterRegistry meterRegistry) {
        this.repo = repo;
        this.cartClient = cartClient;
        this.productClient = productClient;
        this.orderEventStore = orderEventStore;
        this.reservationsReserved = Counter.builder("checkout.reservations.reserved").register(meterRegistry);
        this.reservationsReleased = Counter.builder("checkout.reservations.released").register(meterRegistry);
    }

    @Transactional
    public Order createOrder(Order order) {
        return createOrder(order, null);
    }

    @Transactional
    public Order createOrder(Order order, String correlationId) {
        if (order.getIdempotencyKey() == null || order.getIdempotencyKey().isBlank()) {
            order.setIdempotencyKey(UUID.randomUUID().toString());
        }
        order.setStatus(OrderStatus.PENDING);
        order.setOrderDate(LocalDateTime.now());
        order.setCorrelationId(correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId);
        Order savedOrder = repo.save(order);
        log.info("EVENT=ORDER_CREATED ORDER_ID={} USER_ID={} TOTAL={} CORRELATION_ID={}",
            savedOrder.getId(), savedOrder.getUserId(), savedOrder.getTotal(), savedOrder.getCorrelationId());
        return savedOrder;
    }

    @Transactional
    public Order checkout(Long cartId, String idempotencyKey, String correlationId,
                          AuthenticatedCustomer customer, String authorizationHeader) {
        String resolvedKey = resolveIdempotencyKey(idempotencyKey);
        Optional<Order> existingOrder = findOrderFor(customer, resolvedKey);
        if (existingOrder.isPresent()) {
            log.info("EVENT=CHECKOUT_IDEMPOTENT_REPLAY ORDER_ID={} USER_ID={} KEY={}",
                    existingOrder.get().getId(), customer.customerId(), resolvedKey);
            return existingOrder.get();
        }

        CartSnapshot cartSnapshot = cartClient.getCart(cartId, authorizationHeader);
        if (!cartSnapshot.isOpen()) {
            throw new CheckoutCartAlreadyCheckedOutException(cartId);
        }
        if (cartSnapshot.items() == null || cartSnapshot.items().isEmpty()) {
            throw new EmptyCartException(cartId);
        }
        if (cartSnapshot.currency() == null || !cartSnapshot.currency().matches("[A-Z]{3}")) {
            throw new IllegalStateException("Cart currency is invalid");
        }

        // Reserve stock for every cart line. Reservations are idempotent by key
        // (checkout key + product id), and any failure releases the lines that
        // were already reserved so a retry does not leak stock.
        List<ReservationHandle> reservations = new ArrayList<>();
        try {
            for (CartItemSnapshot item : cartSnapshot.items()) {
                String reservationKey = reservationKeyFor(resolvedKey, customer.customerId(), item.productId());
                productClient.reserve(item.productId(), item.quantity(), reservationKey, authorizationHeader);
                reservations.add(new ReservationHandle(reservationKey, item.productId()));
                reservationsReserved.increment();
                log.info("EVENT=ORDER_RESERVED PRODUCT_ID={} QUANTITY={} KEY={}",
                        item.productId(), item.quantity(), reservationKey);
            }
        } catch (RuntimeException ex) {
            releaseReservations(reservations, authorizationHeader);
            throw ex;
        }

        try {
            List<OrderLine> orderLines = cartSnapshot.items().stream()
                    .map(this::toOrderLine)
                    .toList();
            Order order = new Order();
            order.setCartId(cartId);
            order.setUserId(customer.customerId());
            order.setIdempotencyKey(resolvedKey);
            order.setOrderLines(orderLines);
            order.setTotal(orderLines.stream().mapToDouble(OrderLine::getLineTotal).sum());
            order.setCurrency(cartSnapshot.currency());
            // Atomically claim the cart BEFORE persisting the order. The cart
            // service guards this with an optimistic lock, so a concurrent
            // checkout that already claimed the cart fails here and never
            // writes a second order for the same cart.
            cartClient.markCheckedOut(cartId, authorizationHeader);
            try {
                return createOrder(order, correlationId);
            } catch (DataIntegrityViolationException ex) {
                // Two requests raced with the same idempotency key past the
                // replay check and the unique (user_id, idempotency_key) index
                // rejected our insert. The winning order already owns stock for
                // this key, so surface it as an idempotent replay instead of a 500.
                log.info("EVENT=CHECKOUT_IDEMPOTENT_RACE_USER_ID={} KEY={}",
                        customer.customerId(), resolvedKey);
                return findOrderFor(customer, resolvedKey)
                        .orElseThrow(() -> ex);
            } catch (RuntimeException ex) {
                // Order write failed after the cart was claimed; reopen the
                // cart so the customer can retry checkout instead of being stranded.
                try {
                    cartClient.markOpen(cartId, authorizationHeader);
                } catch (RuntimeException revertFailure) {
                    log.error("EVENT=CART_REVERT_FAILED CART_ID={} REASON={}", cartId,
                            revertFailure.getClass().getSimpleName());
                }
                throw ex;
            }
        } catch (RuntimeException ex) {
            // The order write failed; compensate so stock is not leaked.
            releaseReservations(reservations, authorizationHeader);
            throw ex;
        }
    }

    public Order getOrder(Long id, AuthenticatedCustomer customer) {
        return repo.findByIdAndUserId(id, customer.customerId())
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public List<Order> getOrdersForUser(AuthenticatedCustomer customer) {
        return repo.findByUserIdOrderByOrderDateDesc(customer.customerId());
    }

    @Transactional
    public Order updateOrderStatus(Long id, OrderStatus newStatus, AuthenticatedCustomer customer) {
        getOrder(id, customer);
        throw new InvalidOrderStateException("Order status changes are managed by the payment lifecycle");
    }

    @Transactional
    public Order completePayment(Long orderId) {
        Order order = repo.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() == OrderStatus.COMPLETED) {
            return order;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Cannot complete payment for a " + order.getStatus() + " order");
        }
        order.setStatus(OrderStatus.COMPLETED);
        Order paidOrder = repo.save(order);
        UUID eventId = UUID.randomUUID();
        orderEventStore.enqueue(new OrderCreatedEvent(eventId, Instant.now(), paidOrder.getId(),
                paidOrder.getUserId(), paidOrder.getCartId(), paidOrder.getTotal(),
                paidOrder.getCorrelationId(), paidOrder.getCurrency()));
        log.info("EVENT=ORDER_PAYMENT_COMPLETED ORDER_ID={} CURRENCY={}",
                paidOrder.getId(), paidOrder.getCurrency());
        return paidOrder;
    }

    @Transactional
    public Order expirePayment(Long orderId) {
        Order order = repo.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return order;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Cannot expire payment for a " + order.getStatus() + " order");
        }
        for (OrderLine line : order.getOrderLines()) {
            productClient.releaseAfterPaymentExpiry(
                    reservationKeyFor(order.getIdempotencyKey(), order.getUserId(), line.getProductId()));
        }
        cartClient.markOpen(order.getCartId(), null);
        order.setStatus(OrderStatus.CANCELLED);
        Order cancelledOrder = repo.save(order);
        log.info("EVENT=ORDER_PAYMENT_EXPIRED ORDER_ID={}", cancelledOrder.getId());
        return cancelledOrder;
    }

    private OrderLine toOrderLine(CartItemSnapshot item) {
        return new OrderLine(item.productId(), item.productName(), item.price(), item.quantity());
    }

    private Optional<Order> findOrderFor(AuthenticatedCustomer customer, String resolvedKey) {
        return repo.findFirstByUserIdAndIdempotencyKey(customer.customerId(), resolvedKey);
    }

    private String resolveIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = idempotencyKey.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("Idempotency key must not exceed 64 characters");
        }
        return trimmed;
    }

    private void releaseReservations(List<ReservationHandle> reservations, String authorizationHeader) {
        for (ReservationHandle reservation : reservations) {
            try {
                productClient.release(reservation.reservationKey(), authorizationHeader);
                reservationsReleased.increment();
                log.info("EVENT=ORDER_RESERVATION_RELEASED PRODUCT_ID={} KEY={}",
                        reservation.productId(), reservation.reservationKey());
            } catch (RuntimeException ex) {
                log.error("EVENT=ORDER_RESERVATION_RELEASE_FAILED KEY={} REASON={}",
                        reservation.reservationKey(), ex.getClass().getSimpleName());
            }
        }
    }

    private String reservationKeyFor(String checkoutKey, String customerId, Long productId) {
        return "checkout:" + customerId + ":" + checkoutKey + ":" + productId;
    }

    private record ReservationHandle(String reservationKey, Long productId) {
    }
}
