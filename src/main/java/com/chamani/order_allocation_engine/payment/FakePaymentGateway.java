package com.chamani.order_allocation_engine.payment;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for a payment provider, with configurable latency, declines and timeouts.
 *
 * It deduplicates on the idempotency key like a real provider: the first call for a key decides the
 * outcome, every later call returns that outcome and never charges again. A timeout models the worst
 * case: the money WAS taken but the answer was lost. A retry with the same key then returns APPROVED
 * without a second charge, and refund(key) gives the money back.
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

    private enum Outcome { APPROVED, DECLINED, TIMEOUT_AFTER_CHARGE }

    private static final class Entry {
        final Outcome outcome;
        final String paymentId;                              // null when declined
        final AtomicBoolean served = new AtomicBoolean();    // the first caller pays the latency and counts the charge
        final AtomicInteger charges = new AtomicInteger();
        final AtomicInteger refundCalls = new AtomicInteger();
        final AtomicInteger refunds = new AtomicInteger();

        Entry(Outcome outcome, String paymentId) {
            this.outcome = outcome;
            this.paymentId = paymentId;
        }
    }

    private final FakePaymentProperties props;
    private final Random random;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public FakePaymentGateway(FakePaymentProperties props) {
        this(props, new Random());
    }

    /** A seeded Random makes the declines and timeouts deterministic in tests. */
    public FakePaymentGateway(FakePaymentProperties props, Random random) {
        this.props = props;
        this.random = random;
    }

    @Override
    public PaymentResult charge(UUID orderId, BigDecimal amount, String idempotencyKey) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        // computeIfAbsent only draws the outcome (cheap, nothing sleeps inside it).
        Entry entry = entries.computeIfAbsent(idempotencyKey, k -> decide());

        boolean first = entry.served.compareAndSet(false, true);
        if (first) {
            if (entry.outcome != Outcome.DECLINED) {
                entry.charges.incrementAndGet();
            }
            if (entry.outcome == Outcome.TIMEOUT_AFTER_CHARGE) {
                sleep(props.timeoutMs());
                throw new PaymentTimeoutException(idempotencyKey);
            }
            sleep(props.latencyMs());
        }
        return entry.outcome == Outcome.DECLINED
                ? PaymentResult.declined("Card declined")
                : PaymentResult.approved(entry.paymentId);
    }

    @Override
    public void refund(String idempotencyKey) {
        Entry entry = entries.get(idempotencyKey);
        if (entry == null) {
            return;                                          // never charged: nothing to refund
        }
        entry.refundCalls.incrementAndGet();
        if (entry.charges.get() > 0) {
            entry.refunds.compareAndSet(0, 1);               // refunded once, however often it is asked for
        }
    }

    private Entry decide() {
        if (random.nextDouble() < props.timeoutRate()) {
            return new Entry(Outcome.TIMEOUT_AFTER_CHARGE, "pay-" + UUID.randomUUID());
        }
        if (random.nextDouble() < props.failureRate()) {
            return new Entry(Outcome.DECLINED, null);
        }
        return new Entry(Outcome.APPROVED, "pay-" + UUID.randomUUID());
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted inside the fake payment gateway", e);
        }
    }

    // ---- test-only inspection ---------------------------------------------------------------------------

    /** How many times this key was actually charged (0 if declined or unknown, never more than 1). */
    public int chargeCount(String idempotencyKey) {
        Entry e = entries.get(idempotencyKey);
        return e == null ? 0 : e.charges.get();
    }

    /** How many refunds were actually applied for this key (0 or 1). */
    public int refundCount(String idempotencyKey) {
        Entry e = entries.get(idempotencyKey);
        return e == null ? 0 : e.refunds.get();
    }

    /** How many times refund(key) was called for a key the gateway knows (including repeat calls). */
    public int refundCallCount(String idempotencyKey) {
        Entry e = entries.get(idempotencyKey);
        return e == null ? 0 : e.refundCalls.get();
    }

    /** Forgets every key. */
    public void reset() {
        entries.clear();
    }
}
