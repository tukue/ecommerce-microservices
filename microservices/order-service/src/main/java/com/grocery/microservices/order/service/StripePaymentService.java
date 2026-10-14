package com.grocery.microservices.order.service;

import com.grocery.microservices.order.config.AuthenticatedCustomer;
import com.grocery.microservices.order.dto.PaymentSessionDTO;
import com.grocery.microservices.order.exception.InvalidOrderStateException;
import com.grocery.microservices.order.exception.OrderNotFoundException;
import com.grocery.microservices.order.exception.PaymentUnavailableException;
import com.grocery.microservices.order.exception.CartServiceUnavailableException;
import com.grocery.microservices.order.exception.ProductServiceUnavailableException;
import com.grocery.microservices.order.model.Order;
import com.grocery.microservices.order.model.OrderLine;
import com.grocery.microservices.order.model.OrderStatus;
import com.grocery.microservices.order.model.PaymentAttempt;
import com.grocery.microservices.order.model.PaymentAttemptStatus;
import com.grocery.microservices.order.repository.OrderRepository;
import com.grocery.microservices.order.repository.PaymentAttemptRepository;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

@Service
public class StripePaymentService {
    private static final String SUPPORTED_CURRENCY = "SEK";
    private static final Logger log = LoggerFactory.getLogger(StripePaymentService.class);

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final String stripeSecretKey;
    private final String stripeWebhookSecret;
    private final String frontendBaseUrl;

    public StripePaymentService(OrderService orderService,
                                OrderRepository orderRepository,
                                PaymentAttemptRepository paymentAttemptRepository,
                                @Value("${stripe.secret-key:}") String stripeSecretKey,
                                @Value("${stripe.webhook-secret:}") String stripeWebhookSecret,
                                @Value("${stripe.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.stripeSecretKey = stripeSecretKey;
        this.stripeWebhookSecret = stripeWebhookSecret;
        this.frontendBaseUrl = validateFrontendBaseUrl(frontendBaseUrl);
    }

    @Transactional
    public PaymentSessionDTO createCheckoutSession(Long orderId, AuthenticatedCustomer customer) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .filter(candidate -> customer.customerId().equals(candidate.getUserId()))
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Payment is only available for a pending order");
        }
        if (!SUPPORTED_CURRENCY.equals(order.getCurrency())) {
            throw new InvalidOrderStateException("Stripe checkout currently supports SEK orders only");
        }

        PaymentAttempt previous = paymentAttemptRepository
                .findFirstByOrderIdOrderByAttemptNumberDesc(orderId).orElse(null);
        if (previous != null && previous.getStatus() == PaymentAttemptStatus.OPEN) {
            return new PaymentSessionDTO(previous.getCheckoutUrl());
        }
        int attemptNumber = previous == null ? 1 : previous.getAttemptNumber() + 1;
        Session session = createStripeSession(order, attemptNumber);
        if (session.getId() == null || session.getUrl() == null) {
            throw new PaymentUnavailableException();
        }

        PaymentAttempt attempt = new PaymentAttempt();
        attempt.setOrderId(orderId);
        attempt.setAttemptNumber(attemptNumber);
        attempt.setStripeSessionId(session.getId());
        attempt.setCheckoutUrl(session.getUrl());
        attempt.setStatus(PaymentAttemptStatus.OPEN);
        paymentAttemptRepository.save(attempt);
        return new PaymentSessionDTO(session.getUrl());
    }

    @Transactional
    public void handleWebhook(String payload, String signature) {
        if (stripeWebhookSecret == null || stripeWebhookSecret.isBlank()) {
            throw new PaymentUnavailableException();
        }
        final Event event;
        try {
            event = Webhook.constructEvent(payload, signature, stripeWebhookSecret);
        } catch (Exception ex) {
            throw new InvalidStripeWebhookException();
        }
        if (!event.getType().equals("checkout.session.completed")
                && !event.getType().equals("checkout.session.async_payment_succeeded")
                && !event.getType().equals("checkout.session.async_payment_failed")
                && !event.getType().equals("checkout.session.expired")) {
            return;
        }

        StripeObject stripeObject = event.getDataObjectDeserializer().getObject()
                .orElseThrow(InvalidStripeWebhookException::new);
        if (!(stripeObject instanceof Session session)) {
            throw new InvalidStripeWebhookException();
        }
        long orderId = parseOrderId(session);
        PaymentAttempt attempt = requireAttempt(session, orderId);
        if (event.getType().equals("checkout.session.expired")
                || event.getType().equals("checkout.session.async_payment_failed")) {
            expireSession(attempt, orderId);
            return;
        }
        if (!"paid".equals(session.getPaymentStatus())) {
            return;
        }
        orderService.completePayment(orderId);
        updateAttempt(attempt, PaymentAttemptStatus.PAID);
    }

    @Scheduled(fixedDelayString = "${app.payments.expiry-scan-delay-ms:60000}")
    public void reconcileExpiredCheckouts() {
        var staleOrderIds = orderRepository.findOrderIdsByStatusAndOrderDateBefore(
                OrderStatus.PENDING, java.time.LocalDateTime.now().minusMinutes(45));
        for (Long orderId : staleOrderIds) {
            try {
                PaymentAttempt attempt = paymentAttemptRepository
                        .findFirstByOrderIdOrderByAttemptNumberDesc(orderId).orElse(null);
                if (attempt == null) {
                    orderService.expirePayment(orderId);
                    continue;
                }
                if (attempt.getStatus() != PaymentAttemptStatus.OPEN) {
                    continue;
                }
                if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
                    log.error("EVENT=STALE_PAYMENT_RECONCILIATION_BLOCKED ORDER_ID={} REASON=STRIPE_KEY_MISSING",
                            orderId);
                    continue;
                }
                RequestOptions options = RequestOptions.builder().setApiKey(stripeSecretKey).build();
                Session session = Session.retrieve(attempt.getStripeSessionId(), options);
                PaymentAttempt matchingAttempt = requireAttempt(session, orderId);
                if ("paid".equals(session.getPaymentStatus())) {
                    orderService.completePayment(orderId);
                    updateAttempt(matchingAttempt, PaymentAttemptStatus.PAID);
                } else if ("expired".equals(session.getStatus())) {
                    expireSession(matchingAttempt, orderId);
                }
            } catch (com.stripe.exception.StripeException ex) {
                log.error("EVENT=STALE_PAYMENT_RECONCILIATION_FAILED ORDER_ID={} REASON={}",
                        orderId, ex.getClass().getSimpleName());
            } catch (InvalidOrderStateException ex) {
                log.info("EVENT=STALE_PAYMENT_RECONCILIATION_SKIPPED ORDER_ID={} REASON=STATE_CHANGED", orderId);
            } catch (CartServiceUnavailableException | ProductServiceUnavailableException ex) {
                log.error("EVENT=STALE_PAYMENT_COMPENSATION_FAILED ORDER_ID={} REASON={}",
                        orderId, ex.getClass().getSimpleName());
            }
        }
    }

    private Session createStripeSession(Order order, int attemptNumber) {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            throw new PaymentUnavailableException();
        }
        try {
            SessionCreateParams.Builder params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(frontendBaseUrl + "/confirmation/" + order.getId()
                            + "?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(frontendBaseUrl + "/checkout?payment=cancelled&orderId=" + order.getId())
                    .setExpiresAt(Instant.now().plus(35, ChronoUnit.MINUTES).getEpochSecond())
                    .addAllowedPaymentMethodType(SessionCreateParams.AllowedPaymentMethodType.CARD)
                    .putMetadata("orderId", order.getId().toString())
                    .putMetadata("attemptNumber", Integer.toString(attemptNumber));

            BigDecimal expectedTotal = BigDecimal.ZERO;
            for (OrderLine line : order.getOrderLines()) {
                long unitAmount = BigDecimal.valueOf(line.getUnitPrice())
                        .setScale(2, RoundingMode.UNNECESSARY)
                        .movePointRight(2)
                        .longValueExact();
                if (unitAmount <= 0 || line.getQuantity() <= 0) {
                    throw new InvalidOrderStateException("Order contains an invalid payment amount");
                }
                expectedTotal = expectedTotal.add(BigDecimal.valueOf(unitAmount)
                        .multiply(BigDecimal.valueOf(line.getQuantity())));
                params.addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity((long) line.getQuantity())
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(order.getCurrency().toLowerCase(Locale.ROOT))
                                .setUnitAmount(unitAmount)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName(line.getProductName())
                                        .build())
                                .build())
                        .build());
            }
            long orderTotal = BigDecimal.valueOf(order.getTotal())
                    .setScale(2, RoundingMode.HALF_UP)
                    .movePointRight(2)
                    .longValueExact();
            if (expectedTotal.longValueExact() != orderTotal) {
                throw new InvalidOrderStateException("Order total does not match its payment lines");
            }
            RequestOptions options = RequestOptions.builder()
                    .setApiKey(stripeSecretKey)
                    .setIdempotencyKey("order-" + order.getId() + "-attempt-" + attemptNumber)
                    .build();
            return Session.create(params.build(), options);
        } catch (StripeException | ArithmeticException ex) {
            throw new PaymentUnavailableException(ex);
        }
    }

    private PaymentAttempt requireAttempt(Session session, long orderId) {
        if (session.getId() == null) {
            throw new InvalidStripeWebhookException();
        }
        PaymentAttempt attempt = paymentAttemptRepository.findByStripeSessionId(session.getId())
                .orElseThrow(InvalidStripeWebhookException::new);
        if (!Long.valueOf(orderId).equals(attempt.getOrderId())) {
            throw new InvalidStripeWebhookException();
        }
        return attempt;
    }

    @Transactional
    protected void expireSession(PaymentAttempt attempt, long orderId) {
        if (attempt.getStatus() != PaymentAttemptStatus.OPEN) {
            return;
        }
        orderService.expirePayment(orderId);
        updateAttempt(attempt, PaymentAttemptStatus.EXPIRED);
    }

    private void updateAttempt(PaymentAttempt attempt, PaymentAttemptStatus status) {
        attempt.setStatus(status);
        paymentAttemptRepository.save(attempt);
    }

    private long parseOrderId(Session session) {
        Map<String, String> metadata = session.getMetadata();
        try {
            String rawOrderId = metadata == null ? null : metadata.get("orderId");
            return Long.parseLong(rawOrderId);
        } catch (RuntimeException ex) {
            throw new InvalidStripeWebhookException();
        }
    }

    private String validateFrontendBaseUrl(String value) {
        URI uri = URI.create(value);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalStateException("stripe.frontend-base-url must be an HTTP(S) origin or base path");
        }
        return value.replaceAll("/+$", "");
    }
}
