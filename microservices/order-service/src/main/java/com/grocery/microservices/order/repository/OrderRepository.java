package com.grocery.microservices.order.repository;

import com.grocery.microservices.order.model.Order;
import com.grocery.microservices.order.model.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

public interface OrderRepository extends JpaRepository<Order, Long> {
    @Override
    @EntityGraph(attributePaths = "orderLines")
    Optional<Order> findById(Long id);

    @EntityGraph(attributePaths = "orderLines")
    List<Order> findByUserIdOrderByOrderDateDesc(String userId);

    @EntityGraph(attributePaths = "orderLines")
    Optional<Order> findByIdAndUserId(Long id, String userId);

    @EntityGraph(attributePaths = "orderLines")
    Optional<Order> findFirstByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Query("select o.id from Order o where o.status = :status and o.orderDate < :cutoff")
    List<Long> findOrderIdsByStatusAndOrderDateBefore(@Param("status") OrderStatus status,
                                                       @Param("cutoff") LocalDateTime cutoff);
}
