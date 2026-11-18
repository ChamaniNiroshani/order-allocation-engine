package com.chamani.order_allocation_engine.controller;

import com.chamani.order_allocation_engine.dto.CreateOrderRequest;
import com.chamani.order_allocation_engine.dto.OrderResponse;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import com.chamani.order_allocation_engine.service.OrderQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderAllocationFacade allocationFacade;
    private final OrderQueryService orderQueryService;

    public OrderController(OrderAllocationFacade allocationFacade, OrderQueryService orderQueryService) {
        this.allocationFacade = allocationFacade;
        this.orderQueryService = orderQueryService;
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderQueryService.get(id));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        Order order = allocationFacade.allocate(
                request.getItemId(),
                request.getQuantityRequested(),
                idempotencyKey
        );

        boolean allocated = order.getStatus() == OrderStatus.ALLOCATED;
        OrderResponse response = OrderResponse.from(order);

        // 201 = stock allocated, 409 = rejected (conflicts with current stock). A retry with the
        // same Idempotency-Key returns the stored order, so it gets the same status code again.
        HttpStatus status = allocated ? HttpStatus.CREATED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(response);
    }
}
