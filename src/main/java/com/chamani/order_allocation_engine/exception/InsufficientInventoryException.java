package com.chamani.order_allocation_engine.exception;

public class InsufficientInventoryException extends RuntimeException {
    private final Integer quantityAvailable;

    public InsufficientInventoryException(Integer quantityAvailable) {
        super("Insufficient inventory: only " + quantityAvailable + " available");
        this.quantityAvailable = quantityAvailable;
    }

    public Integer getQuantityAvailable() { return quantityAvailable; }
}
