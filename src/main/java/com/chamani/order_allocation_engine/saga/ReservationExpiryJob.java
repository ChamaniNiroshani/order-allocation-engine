package com.chamani.order_allocation_engine.saga;

import com.chamani.order_allocation_engine.metrics.AllocationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.BiFunction;

/**
 * Cleans up after the checkout saga: expires reservations that never got paid, and finishes orders that
 * were paid but not confirmed (a crash between the two). Not transactional itself: every batch is its own
 * transaction on {@link ReservationExpiryService}.
 *
 * The scheduled entry point does nothing when checkout.expiry.enabled=false; tests call
 * {@link #expireStale()} and {@link #recoverPaid()} directly.
 */
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);

    private final ReservationExpiryService service;
    private final CheckoutProperties properties;
    private final AllocationMetrics metrics;

    public ReservationExpiryJob(ReservationExpiryService service, CheckoutProperties properties,
                                AllocationMetrics metrics) {
        this.service = service;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${checkout.expiry.interval:30s}", initialDelayString = "${checkout.expiry.interval:30s}")
    public void scheduledRun() {
        if (!properties.expiry().enabled()) {
            return;
        }
        try {
            int expired = expireStale();
            int confirmed = recoverPaid();
            if (expired > 0 || confirmed > 0) {
                log.info("Checkout cleanup: expired {} stale reservation(s), confirmed {} stuck paid order(s)", expired, confirmed);
            }
        } catch (RuntimeException e) {
            log.error("Checkout cleanup failed, will retry on the next run", e);
        }
    }

    /** Expires every RESERVED order older than the TTL, one batch (one transaction) at a time. */
    public int expireStale() {
        return drain((cutoff, batchSize) -> {
            int expired = service.expireBatch(cutoff, batchSize);       // committed by now
            metrics.compensation(AllocationMetrics.REASON_EXPIRED, expired);
            return expired;
        });
    }

    /** Confirms every PAID order older than the TTL (charged, never confirmed). */
    public int recoverPaid() {
        return drain(service::confirmPaidBatch);
    }

    private int drain(BiFunction<Instant, Integer, Integer> batch) {
        Instant cutoff = Instant.now().minus(properties.reservationTtl());
        int batchSize = properties.expiry().batchSize();
        int total = 0;
        int done;
        do {
            done = batch.apply(cutoff, batchSize);
            total += done;
        } while (done == batchSize);
        return total;
    }
}
