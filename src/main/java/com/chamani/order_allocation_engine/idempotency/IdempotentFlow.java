package com.chamani.order_allocation_engine.idempotency;

/** The endpoints that take an Idempotency-Key. A key belongs to the one that used it first. */
public enum IdempotentFlow {

    ORDER("POST /api/orders"),
    CHECKOUT("POST /api/checkout"),
    FULFILMENT("POST /api/fulfilments");

    private final String endpoint;

    IdempotentFlow(String endpoint) {
        this.endpoint = endpoint;
    }

    public String endpoint() {
        return endpoint;
    }
}
