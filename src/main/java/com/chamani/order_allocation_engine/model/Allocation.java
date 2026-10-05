package com.chamani.order_allocation_engine.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "allocation")
public class Allocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID allocationId;

    @NotNull
    private UUID orderId;

    @NotNull
    private UUID itemId;

    private Integer quantityAllocated;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private Instant timestamp = Instant.now();

    protected Allocation() {}

    public Allocation(UUID orderId, UUID itemId, Integer quantityAllocated, OrderStatus status) {
        this.orderId = orderId;
        this.itemId = itemId;
        this.quantityAllocated = quantityAllocated;
        this.status = status;
    }

    public UUID getAllocationId() { return allocationId; }
    public UUID getOrderId() { return orderId; }
    public UUID getItemId() { return itemId; }
    public Integer getQuantityAllocated() { return quantityAllocated; }
    public OrderStatus getStatus() { return status; }
    public Instant getTimestamp() { return timestamp; }
}
