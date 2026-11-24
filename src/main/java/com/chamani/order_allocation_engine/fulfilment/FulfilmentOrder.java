package com.chamani.order_allocation_engine.fulfilment;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fulfilment_order")
public class FulfilmentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "delivery_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal deliveryLat;

    @Column(name = "delivery_lon", nullable = false, precision = 9, scale = 6)
    private BigDecimal deliveryLon;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_tier", nullable = false, length = 16)
    private CustomerTier customerTier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StrategyType strategy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private FulfilmentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    // SHA-256 of the request payload, so a reused Idempotency-Key with a different request is detected (same convention as orders)
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FulfilmentOrder() {}

    public FulfilmentOrder(String sku, Integer quantity, BigDecimal deliveryLat, BigDecimal deliveryLon,
                           CustomerTier customerTier, StrategyType strategy, String idempotencyKey, String requestHash) {
        this.sku = sku;
        this.quantity = quantity;
        this.deliveryLat = deliveryLat;
        this.deliveryLon = deliveryLon;
        this.customerTier = customerTier;
        this.strategy = strategy;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = FulfilmentStatus.FAILED;
    }

    public void markAllocated() {
        this.status = FulfilmentStatus.ALLOCATED;
        this.failureReason = null;
    }

    public void markFailed(String reason) {
        this.status = FulfilmentStatus.FAILED;
        this.failureReason = reason;
    }

    public boolean matchesRequestHash(String hash) {
        return requestHash == null || requestHash.equals(hash);
    }

    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public Integer getQuantity() { return quantity; }
    public BigDecimal getDeliveryLat() { return deliveryLat; }
    public BigDecimal getDeliveryLon() { return deliveryLon; }
    public CustomerTier getCustomerTier() { return customerTier; }
    public StrategyType getStrategy() { return strategy; }
    public FulfilmentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
