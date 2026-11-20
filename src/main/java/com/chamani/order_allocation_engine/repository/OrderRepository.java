package com.chamani.order_allocation_engine.repository;

import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    long countByStatus(OrderStatus status);

    // SELECT ... FOR UPDATE on the order. Every saga step that changes an order's status takes this
    // lock first and then checks the current status, so two actors (the saga, the expiry job) can
    // never both act on the same order.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderId = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") UUID orderId);

    // Expiry job: the oldest orders in a status that were reserved before the cutoff, locked for update.
    // SKIP LOCKED means a row another transaction holds (a saga step, another job instance) is passed
    // over instead of waited for, so the job never blocks a payment and two jobs never share a row.
    @Query(value = """
            SELECT * FROM orders
             WHERE status = :status AND reserved_at < :cutoff
             ORDER BY reserved_at
             LIMIT :batch
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Order> lockOlderThan(@Param("status") String status,
                              @Param("cutoff") Instant cutoff,
                              @Param("batch") int batch);
}
