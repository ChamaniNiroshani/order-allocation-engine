package com.chamani.order_allocation_engine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class CreateOrderRequest {

    @NotNull(message = "itemId is required")
    private UUID itemId;

    @NotNull(message = "quantityRequested is required")
    @Min(value = 1, message = "quantityRequested must be at least 1")
    private Integer quantityRequested;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }
    public Integer getQuantityRequested() { return quantityRequested; }
    public void setQuantityRequested(Integer quantityRequested) { this.quantityRequested = quantityRequested; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
