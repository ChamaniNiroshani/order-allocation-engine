package com.chamani.order_allocation_engine.saga;

import com.chamani.order_allocation_engine.logging.OrderEvents;
import com.chamani.order_allocation_engine.logging.OrderMdc;
import com.chamani.order_allocation_engine.metrics.AllocationMetrics;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.payment.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Cleans up after the checkout saga: expires reservations that never got paid, and finishes orders that
 * were paid but not confirmed (a crash between the two). Not transactional itself: every batch is its own
 * transaction on {@link ReservationExpiryService}, and the gateway is only called between those transactions.
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
    private final PaymentGateway paymentGateway;

    public ReservationExpiryJob(ReservationExpiryService service, CheckoutProperties properties,
                                AllocationMetrics metrics, PaymentGateway paymentGateway) {
        this.service = service;
        this.paymentGateway = paymentGateway;
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
                OrderEvents.reservationsCleanedUp(expired, confirmed);
            }
        } catch (RuntimeException e) {
            log.error("Checkout cleanup failed, will retry on the next run", e);
        }
    }

    /** Expires every RESERVED order older than the TTL, one batch (one transaction) at a time, then refunds each. */
    public int expireStale() {
        return drain((cutoff, batchSize) -> {
            List<Order> expired = service.expireBatch(cutoff, batchSize);       // committed by now
            metrics.compensation(AllocationMetrics.REASON_EXPIRED, expired.size());
            expired.forEach(this::refund);
            return expired.size();
        });
    }

    /** Confirms every PAID order older than the TTL (charged, never confirmed). */
    public int recoverPaid() {
        return drain(service::confirmPaidBatch);
    }

    /**
     * An expired order may have been charged: the app can crash after the charge and before the order is marked PAID.
     * Refunding every expired order is safe because the gateway deduplicates by idempotency key: refunding a key that
     * was never charged is a no-op, and so is refunding a key twice.
     *
     * Runs after the expiry has committed, so no lock or transaction is open during the remote call. A failure never
     * undoes the expiry and never stops the other refunds. It is not retried: the WARN and the failure counter are
     * the signal that this order needs a manual check.
     */
    private void refund(Order order) {
        UUID orderId = order.getOrderId();
        try (var ignored = OrderMdc.put(orderId)) {
            try {
                paymentGateway.refund(order.getIdempotencyKey());
            } catch (RuntimeException e) {
                metrics.refundFailure();
                // Only the exception type: a gateway's message can carry the idempotency key, which is never logged.
                log.warn("Refund failed for expired order {} ({})", orderId, e.getClass().getName());
                return;
            }
            metrics.refund();
            try {
                service.recordRefundRequest(orderId);
            } catch (RuntimeException e) {
                log.warn("Refund requested for expired order {} but the audit entry could not be written", orderId, e);
            }
        }
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
