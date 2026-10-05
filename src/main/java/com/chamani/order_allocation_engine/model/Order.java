package com.chamani.order_allocation_engine.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders", uniqueConstraints = @UniqueConstraint(columnNames = "idempotencyKey"))
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID orderId;

    @NotNull
    private UUID itemId;

    @Min(1)
    private Integer quantityRequested;

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.PENDING;

    @NotBlank
    private String idempotencyKey;

    @Column(updatable = false)
    private Instant createdAt = Instant.now();

    protected Order() {}

    public Order(UUID itemId, Integer quantityRequested, String idempotencyKey) {
        this.itemId = itemId;
        this.quantityRequested = quantityRequested;
        this.idempotencyKey = idempotencyKey;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getItemId() { return itemId; }
    public Integer getQuantityRequested() { return quantityRequested; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
