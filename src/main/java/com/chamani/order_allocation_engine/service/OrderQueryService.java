package com.chamani.order_allocation_engine.service;

import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.TransactionLog;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.repository.TransactionLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;
    private final TransactionLogRepository transactionLogRepository;

    public OrderQueryService(OrderRepository orderRepository, TransactionLogRepository transactionLogRepository) {
        this.orderRepository = orderRepository;
        this.transactionLogRepository = transactionLogRepository;
    }

    @Transactional(readOnly = true)
    public Order get(UUID orderId) {
        // IllegalArgumentException is mapped to 404 by GlobalExceptionHandler.
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown orderId: " + orderId));
    }

    /** The order's audit trail, oldest first. */
    @Transactional(readOnly = true)
    public List<TransactionLog> history(UUID orderId) {
        if (!orderRepository.existsById(orderId)) {
            throw new IllegalArgumentException("Unknown orderId: " + orderId);
        }
        return transactionLogRepository.findByEntityIdOrderByTimestampAsc(orderId);
    }
}
