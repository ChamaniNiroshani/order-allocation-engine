package com.chamani.order_allocation_engine.controller;

import com.chamani.order_allocation_engine.dto.CreateOrderRequest;
import com.chamani.order_allocation_engine.dto.OrderResponse;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.saga.CheckoutOrchestrator;
import com.chamani.order_allocation_engine.saga.CheckoutOutcomes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Checkout saga, run synchronously. Same request and Idempotency-Key convention as POST /api/orders.
 * 201 confirmed, 409 insufficient stock, 402 payment declined, 502 payment timeout; the body is always the order.
 */
@RestController
@RequestMapping("/api/checkout")
public class CheckoutController {

    private final CheckoutOrchestrator orchestrator;

    public CheckoutController(CheckoutOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> checkout(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        Order order = orchestrator.checkout(request.getItemId(), request.getQuantityRequested(), idempotencyKey);
        return ResponseEntity.status(CheckoutOutcomes.httpStatus(order)).body(OrderResponse.from(order));
    }
}
