package com.chamani.order_allocation_engine.controller;

import com.chamani.order_allocation_engine.dto.CreateOrderRequest;
import com.chamani.order_allocation_engine.dto.OrderResponse;
import com.chamani.order_allocation_engine.exception.InsufficientInventoryException;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.service.AllocationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final AllocationService allocationService;

    public OrderController(AllocationService allocationService) {
        this.allocationService = allocationService;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
        try {
            Order order = allocationService.allocate(
                    request.getItemId(),
                    request.getQuantityRequested(),
                    request.getIdempotencyKey()
            );
            OrderResponse response = new OrderResponse(
                    order.getOrderId(), order.getStatus(), order.getQuantityRequested(), null
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (InsufficientInventoryException ex) {
            OrderResponse response = new OrderResponse(
                    null, OrderStatus.FAILED, 0, ex.getMessage()
            );
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }
    }
}
