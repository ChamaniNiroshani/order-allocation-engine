package com.chamani.order_allocation_engine.dto;

import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import java.util.UUID;

public class OrderResponse {

    private UUID orderId;
    private OrderStatus status;
    private Integer quantityAllocated;
    private String reason;

    public OrderResponse(UUID orderId, OrderStatus status, Integer quantityAllocated, String reason) {
        this.orderId = orderId;
        this.status = status;
        this.quantityAllocated = quantityAllocated;
        this.reason = reason;
    }

    /** quantityAllocated is the requested quantity for ALLOCATED orders and 0 otherwise. */
    public static OrderResponse from(Order order) {
        boolean allocated = order.getStatus() == OrderStatus.ALLOCATED;
        return new OrderResponse(
                order.getOrderId(),
                order.getStatus(),
                allocated ? order.getQuantityRequested() : 0,
                order.getFailureReason()
        );
    }

    public UUID getOrderId() { return orderId; }
    public OrderStatus getStatus() { return status; }
    public Integer getQuantityAllocated() { return quantityAllocated; }
    public String getReason() { return reason; }
}
