package com.chamani.order_allocation_engine.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class CreateInventoryRequest {

    @NotNull(message = "quantityAvailable is required")
    @Min(value = 0, message = "quantityAvailable must not be negative")
    private Integer quantityAvailable;

    public Integer getQuantityAvailable() { return quantityAvailable; }
    public void setQuantityAvailable(Integer quantityAvailable) { this.quantityAvailable = quantityAvailable; }
}
