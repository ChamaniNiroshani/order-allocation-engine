package com.chamani.order_allocation_engine.service;

import com.chamani.order_allocation_engine.concurrency.OptimisticRetryExecutor;
import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.metrics.AllocationMetrics;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Deliberately NOT @Transactional and a separate bean from AllocationService: the catch below
 * must run after the AllocationService proxy has committed or rolled back. A self-call inside
 * AllocationService would skip the proxy, and the unique-constraint violation only surfaces
 * at commit anyway.
 */
@Service
public class OrderAllocationFacade {

    private static final Logger log = LoggerFactory.getLogger(OrderAllocationFacade.class);

    private final AllocationService allocationService;
    private final OrderRepository orderRepository;
    private final OptimisticRetryExecutor optimisticRetry;
    private final AllocationMetrics metrics;

    public OrderAllocationFacade(AllocationService allocationService, OrderRepository orderRepository,
                                 OptimisticRetryExecutor optimisticRetry, AllocationMetrics metrics) {
        this.allocationService = allocationService;
        this.orderRepository = orderRepository;
        this.optimisticRetry = optimisticRetry;
        this.metrics = metrics;
    }

    public Order allocate(UUID itemId, Integer quantityRequested, String idempotencyKey) {
        return metrics.measure(AllocationMetrics.Flow.DIRECT, () -> doAllocate(itemId, quantityRequested, idempotencyKey));
    }

    private Order doAllocate(UUID itemId, Integer quantityRequested, String idempotencyKey) {
        try {
            // Each attempt is a fresh transaction through the AllocationService proxy. A no-op
            // unless the optimistic strategy loses a version race.
            return optimisticRetry.execute(
                    () -> allocationService.allocate(itemId, quantityRequested, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            log.info("Idempotency conflict for key {}, checking the stored order", idempotencyKey);
            Order stored = orderRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            if (!stored.matchesRequestHash(RequestHash.of(itemId, quantityRequested))) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return stored;
        }
    }
}
