package com.chamani.order_allocation_engine.exception;

import com.chamani.order_allocation_engine.idempotency.IdempotentFlow;

/**
 * Thrown when an Idempotency-Key is sent again for something else: with a different request payload, or to another
 * endpoint than the one that used it first.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        this(idempotencyKey, "with a different request payload");
    }

    private IdempotencyKeyReusedException(String idempotencyKey, String how) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used " + how);
    }

    public static IdempotencyKeyReusedException byAnotherFlow(String idempotencyKey, IdempotentFlow owner) {
        return new IdempotencyKeyReusedException(idempotencyKey,
                "by a different operation (" + owner.endpoint() + "). Use a new key for each operation");
    }
}
