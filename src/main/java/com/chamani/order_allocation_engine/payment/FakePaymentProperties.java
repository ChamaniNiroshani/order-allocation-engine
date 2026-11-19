package com.chamani.order_allocation_engine.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * payment.fake.*: how the fake gateway misbehaves.
 *
 * @param failureRate probability (0-1) that a charge is declined
 * @param latencyMs   delay of every charge
 * @param timeoutRate probability (0-1) that a charge times out
 * @param timeoutMs   how long a timing-out call blocks before it throws
 */
@ConfigurationProperties(prefix = "payment.fake")
public record FakePaymentProperties(
        @DefaultValue("0.0") double failureRate,
        @DefaultValue("50") long latencyMs,
        @DefaultValue("0.0") double timeoutRate,
        @DefaultValue("2000") long timeoutMs) {

    public FakePaymentProperties {
        if (failureRate < 0 || failureRate > 1) {
            throw new IllegalArgumentException("payment.fake.failure-rate must be between 0 and 1, was " + failureRate);
        }
        if (timeoutRate < 0 || timeoutRate > 1) {
            throw new IllegalArgumentException("payment.fake.timeout-rate must be between 0 and 1, was " + timeoutRate);
        }
        if (latencyMs < 0 || timeoutMs < 0) {
            throw new IllegalArgumentException("payment.fake.latency-ms and timeout-ms must not be negative");
        }
    }
}
