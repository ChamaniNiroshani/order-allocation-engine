package com.chamani.order_allocation_engine.exception;

/**
 * Thrown when an Idempotency-Key that was already used for one request payload
 * (itemId + quantity) is sent again with a different payload.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request payload");
    }
}
