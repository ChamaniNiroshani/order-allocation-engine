package com.chamani.order_allocation_engine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public class CreateOrderRequest {

    @NotNull(message = "itemId is required")
    private UUID itemId;

    @NotNull(message = "quantityRequested is required")
    @Min(value = 1, message = "quantityRequested must be at least 1")
    private Integer quantityRequested;

    public UUID getItemId() { return itemId; }
    public void setItemId(UUID itemId) { this.itemId = itemId; }
    public Integer getQuantityRequested() { return quantityRequested; }
    public void setQuantityRequested(Integer quantityRequested) { this.quantityRequested = quantityRequested; }
}
