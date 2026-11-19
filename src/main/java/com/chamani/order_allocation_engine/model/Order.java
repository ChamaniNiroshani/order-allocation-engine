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

    // Why the order was rejected. Stored so an idempotent retry returns the same answer.
    private String failureReason;

    // SHA-256 of the request payload (itemId + quantity). Nullable on purpose: rows created before
    // this column existed have none, and ddl-auto=update cannot add a NOT NULL column to a table
    // that already has rows.
    @Column(length = 64)
    private String requestHash;

    // Saga bookkeeping. reservedAt drives the expiry job; paymentId is the gateway's reference.
    private Instant reservedAt;

    private Instant updatedAt = Instant.now();

    private String paymentId;

    protected Order() {}

    public Order(UUID itemId, Integer quantityRequested, String idempotencyKey) {
        this.itemId = itemId;
        this.quantityRequested = quantityRequested;
        this.idempotencyKey = idempotencyKey;
    }

    public Order(UUID itemId, Integer quantityRequested, String idempotencyKey, String requestHash) {
        this(itemId, quantityRequested, idempotencyKey);
        this.requestHash = requestHash;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getItemId() { return itemId; }
    public Integer getQuantityRequested() { return quantityRequested; }
    public OrderStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public String getFailureReason() { return failureReason; }
    public Instant getReservedAt() { return reservedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getPaymentId() { return paymentId; }

    /** True when the stored hash equals the given one, or when there is none (legacy rows). */
    public boolean matchesRequestHash(String hash) {
        return requestHash == null || requestHash.equals(hash);
    }

    // ---- state machine -------------------------------------------------------------------------------
    // Status only changes through these methods. An illegal move throws IllegalStateException.

    private void transitionTo(OrderStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal order transition " + status + " -> " + next + " (order " + orderId + ")");
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    /** Direct flow: PENDING -> ALLOCATED. */
    public void markAllocated() {
        transitionTo(OrderStatus.ALLOCATED);
        this.failureReason = null;
    }

    /** PENDING -> FAILED (insufficient stock). */
    public void markFailed(String reason) {
        transitionTo(OrderStatus.FAILED);
        this.failureReason = reason;
    }

    /** Saga: PENDING -> RESERVED (stock is held). */
    public void reserve() {
        transitionTo(OrderStatus.RESERVED);
        this.reservedAt = this.updatedAt;
    }

    /** Saga: RESERVED -> PAID. */
    public void markPaid(String paymentId) {
        transitionTo(OrderStatus.PAID);
        this.paymentId = paymentId;
    }

    /** Saga: PAID -> CONFIRMED. */
    public void confirm() {
        transitionTo(OrderStatus.CONFIRMED);
    }

    /** Saga compensation: RESERVED -> CANCELLED, or PAID -> CANCELLED (refund case). */
    public void cancel(String reason) {
        transitionTo(OrderStatus.CANCELLED);
        this.failureReason = reason;
    }

    /**
     * A payment that arrived after the order was cancelled or expired was given back. Not a status
     * change: it only records the refund in the reason.
     */
    public void recordRefund() {
        if (status != OrderStatus.EXPIRED && status != OrderStatus.CANCELLED) {
            throw new IllegalStateException("Only an expired or cancelled order can record a refund, was " + status);
        }
        this.failureReason = (failureReason == null || failureReason.isBlank() ? "" : failureReason + "; ") + "payment refunded";
        this.updatedAt = Instant.now();
    }

    /** Saga: RESERVED -> EXPIRED (reservation ran out of time). */
    public void expire() {
        transitionTo(OrderStatus.EXPIRED);
        this.failureReason = "Reservation expired";
    }
}
