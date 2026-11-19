package com.chamani.order_allocation_engine.payment;

import java.math.BigDecimal;
import java.util.UUID;

public interface PaymentGateway {

    /**
     * Charges the amount. Idempotent on {@code idempotencyKey}: the same key never charges twice and
     * returns the same outcome.
     *
     * @throws PaymentTimeoutException if the gateway did not answer (the charge may have happened)
     */
    PaymentResult charge(UUID orderId, BigDecimal amount, String idempotencyKey);

    /** Reverses the charge made with this key. A no-op if that key was never charged or is already refunded. */
    void refund(String idempotencyKey);
}
