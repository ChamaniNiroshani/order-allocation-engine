package com.chamani.order_allocation_engine.controller;

import com.chamani.order_allocation_engine.dto.CreateOrderRequest;
import com.chamani.order_allocation_engine.dto.OrderResponse;
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
        Order order = allocationService.allocate(
                request.getItemId(),
                request.getQuantityRequested(),
                request.getIdempotencyKey()
        );

        boolean allocated = order.getStatus() == OrderStatus.ALLOCATED;
        OrderResponse response = new OrderResponse(
                order.getOrderId(),
                order.getStatus(),
                allocated ? order.getQuantityRequested() : 0,
                order.getFailureReason()
        );

        // 201 = stock allocated, 409 = rejected (conflicts with current stock). A retry with the
        // same idempotency key returns the stored order, so it gets the same status code again.
        HttpStatus status = allocated ? HttpStatus.CREATED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(response);
    }
}
