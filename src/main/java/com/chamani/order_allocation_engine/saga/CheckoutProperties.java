package com.chamani.order_allocation_engine.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * checkout.*: how long a stock reservation may wait for its payment, and how the expiry job runs.
 *
 * @param reservationTtl a RESERVED order older than this is expired and its stock released (default 5m)
 * @param expiry         the scheduled job
 */
@ConfigurationProperties(prefix = "checkout")
public record CheckoutProperties(
        @DefaultValue("5m") Duration reservationTtl,
        @DefaultValue Expiry expiry) {

    /**
     * @param enabled   false turns the scheduled run into a no-op (tests call the job methods directly)
     * @param interval  delay between runs; also read by the @Scheduled annotation (default 30s)
     * @param batchSize orders handled per transaction (default 100)
     */
    public record Expiry(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30s") Duration interval,
            @DefaultValue("100") int batchSize) {

        public Expiry {
            if (batchSize < 1) {
                throw new IllegalArgumentException("checkout.expiry.batch-size must be at least 1, was " + batchSize);
            }
        }
    }

    public CheckoutProperties {
        if (reservationTtl.isNegative() || reservationTtl.isZero()) {
            throw new IllegalArgumentException("checkout.reservation-ttl must be positive, was " + reservationTtl);
        }
    }
}
