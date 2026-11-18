package com.chamani.order_allocation_engine.service;

import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderQueryService {

    private final OrderRepository orderRepository;

    public OrderQueryService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public Order get(UUID orderId) {
        // IllegalArgumentException is mapped to 404 by GlobalExceptionHandler.
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown orderId: " + orderId));
    }
}
