package com.chamani.order_allocation_engine.controller;

import com.chamani.order_allocation_engine.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.chamani.order_allocation_engine.dto.CreateOrderRequest;
import com.chamani.order_allocation_engine.dto.HistoryEntryResponse;
import com.chamani.order_allocation_engine.dto.OrderResponse;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import com.chamani.order_allocation_engine.service.OrderQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.UUID;

@Tag(name = OpenApiConfig.TAG_ORDERS)
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderAllocationFacade allocationFacade;
    private final OrderQueryService orderQueryService;
    private final ObjectMapper objectMapper;

    public OrderController(OrderAllocationFacade allocationFacade, OrderQueryService orderQueryService,
                           ObjectMapper objectMapper) {
        this.allocationFacade = allocationFacade;
        this.orderQueryService = orderQueryService;
        this.objectMapper = objectMapper;
    }

    @Operation(summary = "Get an order's audit trail, oldest first")
    @ApiResponse(responseCode = "200", description = "The audit entries")
    @ApiResponse(responseCode = "400", ref = OpenApiConfig.BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = OpenApiConfig.NOT_FOUND)
    @ApiResponse(responseCode = "503", ref = OpenApiConfig.UNAVAILABLE)
    @GetMapping("/{id}/history")
    public List<HistoryEntryResponse> history(@PathVariable UUID id) {
        return orderQueryService.history(id).stream()
                .map(entry -> HistoryEntryResponse.from(entry, objectMapper))
                .toList();
    }

    @Operation(summary = "Get an order")
    @ApiResponse(responseCode = "200", description = "The order",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(responseCode = "400", ref = OpenApiConfig.BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = OpenApiConfig.NOT_FOUND)
    @ApiResponse(responseCode = "503", ref = OpenApiConfig.UNAVAILABLE)
    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable UUID id) {
        return OrderResponse.from(orderQueryService.get(id));
    }

    @Operation(summary = "Allocate stock for an order (direct flow, idempotent)")
    @ApiResponse(responseCode = "201", description = "Stock allocated",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(responseCode = "409", description = "Rejected: not enough stock. The order is stored as FAILED",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(responseCode = "400", ref = OpenApiConfig.BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = OpenApiConfig.NOT_FOUND)
    @ApiResponse(responseCode = "422", ref = OpenApiConfig.KEY_REUSED)
    @ApiResponse(responseCode = "503", ref = OpenApiConfig.UNAVAILABLE)
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
