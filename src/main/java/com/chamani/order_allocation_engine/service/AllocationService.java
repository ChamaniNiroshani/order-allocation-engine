package com.chamani.order_allocation_engine.service;

import com.chamani.order_allocation_engine.concurrency.AllocationProperties;
import com.chamani.order_allocation_engine.concurrency.DecrementResult;
import com.chamani.order_allocation_engine.concurrency.StockDecrementer;
import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.model.Allocation;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.AllocationRepository;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AllocationService {

    private final OrderRepository orderRepository;
    private final AllocationRepository allocationRepository;
    private final StockDecrementer stockDecrementer;
    private final OrderAuditService orderAudit;

    public AllocationService(OrderRepository orderRepository,
                              AllocationRepository allocationRepository,
                              List<StockDecrementer> decrementers,
                              AllocationProperties properties,
                              OrderAuditService orderAudit) {
        this.orderRepository = orderRepository;
        this.allocationRepository = allocationRepository;
        this.orderAudit = orderAudit;
        this.stockDecrementer = decrementers.stream()
                .filter(d -> d.mode() == properties.lockingMode())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No StockDecrementer for allocation.locking-mode=" + properties.lockingMode()));
    }

    /**
     * Allocates stock for an order.
     * Insufficient stock is a normal business outcome, not an error: the order is saved as FAILED
     * (with a reason) and returned, so the transaction commits and the rejection is recorded.
     * Throwing here would roll the whole transaction back and the FAILED order would vanish.
     */
    @Transactional
    public Order allocate(UUID itemId, Integer quantityRequested, String idempotencyKey) {

        String requestHash = RequestHash.of(itemId, quantityRequested);

        // Replay check BEFORE touching stock, so a retried key never decrements twice. Two
        // concurrent first-time requests with the same key can both miss here; the unique
        // constraint on idempotency_key settles that (see OrderAllocationFacade).
        var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Order stored = existing.get();
            if (!stored.matchesRequestHash(requestHash)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return stored;
        }

        Order order = new Order(itemId, quantityRequested, idempotencyKey, requestHash);
        OrderAuditService.Snapshot before = orderAudit.snapshot(order);

        DecrementResult result = stockDecrementer.decrement(itemId, quantityRequested);
        if (!result.isDecremented()) {
            order.markFailed("Insufficient inventory: only " + result.available() + " available");
            Order failed = orderRepository.save(order);
            orderAudit.record(failed, before);
            return failed;
        }

        // Save the order first so it has a generated orderId
        order.markAllocated();
        Order savedOrder = orderRepository.save(order);
        orderAudit.record(savedOrder, before);

        // Record the allocation against the saved order's id
        Allocation allocation = new Allocation(savedOrder.getOrderId(), itemId, quantityRequested, OrderStatus.ALLOCATED);
        allocationRepository.save(allocation);

        return savedOrder;
    }
}
