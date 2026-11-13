package com.chamani.order_allocation_engine.exception;

/**
 * Thrown when an optimistic allocation lost the version race on every attempt. The request was
 * not processed, so the client may retry it (HTTP 503 with Retry-After).
 */
public class StockContentionException extends RuntimeException {

    private final int retryAfterSeconds;

    public StockContentionException(int attempts, int retryAfterSeconds, Throwable cause) {
        super("Item is under heavy contention, gave up after " + attempts + " attempts", cause);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() { return retryAfterSeconds; }
}
