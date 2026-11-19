package com.chamani.order_allocation_engine.payment;

/**
 * The gateway did not answer in time. The charge may or may not have happened, so the caller must
 * not assume either: it refunds by idempotency key, which is safe because gateways dedupe on that key.
 */
public class PaymentTimeoutException extends RuntimeException {

    public PaymentTimeoutException(String idempotencyKey) {
        super("Payment gateway timed out for key " + idempotencyKey);
    }
}
