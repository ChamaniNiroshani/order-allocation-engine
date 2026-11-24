package com.chamani.order_allocation_engine.fulfilment.strategy;

/** What a strategy needs to know about the order: what, how many, and where to. */
public record FulfilmentRequest(String sku, int quantity, double deliveryLat, double deliveryLon) {

    public FulfilmentRequest {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
    }
}
