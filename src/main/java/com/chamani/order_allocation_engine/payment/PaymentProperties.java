package com.chamani.order_allocation_engine.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * payment.*: settings of the payment integration itself (the fake gateway's behaviour is payment.fake.*).
 *
 * @param timeout how long the saga waits for ONE charge call before giving up (default 10s). Giving up is handled like
 *                a gateway timeout: the charge may have happened, so the saga refunds by idempotency key and compensates.
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(@DefaultValue("10s") Duration timeout) {

    public PaymentProperties {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("payment.timeout must be positive, was " + timeout);
        }
    }
}
