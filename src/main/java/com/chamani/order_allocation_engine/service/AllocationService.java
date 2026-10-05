package com.chamani.order_allocation_engine.service;

import com.chamani.order_allocation_engine.exception.InsufficientInventoryException;
import com.chamani.order_allocation_engine.model.Allocation;
import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.AllocationRepository;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AllocationService {

    private final OrderRepository orderRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final AllocationRepository allocationRepository;

    public AllocationService(OrderRepository orderRepository,
                              InventoryItemRepository inventoryItemRepository,
                              AllocationRepository allocationRepository) {
        this.orderRepository = orderRepository;
        this.inventoryItemRepository = inventoryItemRepository;
        this.allocationRepository = allocationRepository;
    }

    @Transactional
    public Order allocate(UUID itemId, Integer quantityRequested, String idempotencyKey) {

        // Idempotency check first — never allocate twice for the same key
        var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        InventoryItem item = inventoryItemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown itemId: " + itemId));

        Order order = new Order(itemId, quantityRequested, idempotencyKey);

        if (item.getQuantityAvailable() < quantityRequested) {
            order.setStatus(OrderStatus.FAILED);
            orderRepository.save(order);
            throw new InsufficientInventoryException(item.getQuantityAvailable());
        }

        // Decrement inventory
        item.setQuantityAvailable(item.getQuantityAvailable() - quantityRequested);
        inventoryItemRepository.save(item);

        // Save the order first so it has a generated orderId
        order.setStatus(OrderStatus.ALLOCATED);
        Order savedOrder = orderRepository.save(order);

        // Record the allocation against the saved order's id
        Allocation allocation = new Allocation(savedOrder.getOrderId(), itemId, quantityRequested, OrderStatus.ALLOCATED);
        allocationRepository.save(allocation);

        return savedOrder;
    }
}
