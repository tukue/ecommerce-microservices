package com.grocery.microservices.order.repository;

import com.grocery.microservices.order.model.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findFirstByOrderIdOrderByAttemptNumberDesc(Long orderId);

    Optional<PaymentAttempt> findByStripeSessionId(String stripeSessionId);
}
