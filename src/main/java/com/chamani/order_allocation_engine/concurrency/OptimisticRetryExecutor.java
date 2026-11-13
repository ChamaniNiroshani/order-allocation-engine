package com.chamani.order_allocation_engine.concurrency;

import com.chamani.order_allocation_engine.exception.StockContentionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Re-runs an allocation that lost an optimistic-lock race. Each attempt must be a NEW
 * transaction, so this is its own bean and the action it runs is a call through the
 * AllocationService proxy. A retry loop inside the @Transactional method would keep reusing the
 * same, already-doomed transaction (and a self-call would bypass the proxy altogether).
 *
 * Backoff is exponential with jitter: base * 2^(attempt-1), plus a random 0..same amount.
 */
@Component
public class OptimisticRetryExecutor {

    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final long DEFAULT_BASE_DELAY_MS = 10;
    private static final int RETRY_AFTER_SECONDS = 1;

    private final int maxAttempts;
    private final long baseDelayMs;
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong exhausted = new AtomicLong();

    @Autowired
    public OptimisticRetryExecutor() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_DELAY_MS);
    }

    OptimisticRetryExecutor(int maxAttempts, long baseDelayMs) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
    }

    public <T> T execute(Supplier<T> action) {
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    exhausted.incrementAndGet();
                    throw new StockContentionException(maxAttempts, RETRY_AFTER_SECONDS, e);
                }
                retries.incrementAndGet();
                backoff(attempt);
            }
        }
    }

    private void backoff(int attempt) {
        long exp = baseDelayMs << (attempt - 1);
        if (exp <= 0) {
            return;
        }
        long delay = exp + ThreadLocalRandom.current().nextLong(exp + 1);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off", ie);
        }
    }

    /** Total retries so far (a simple counter for now; becomes a Micrometer metric later). */
    public long retryCount() { return retries.get(); }

    /** Requests that gave up after max attempts. */
    public long exhaustedCount() { return exhausted.get(); }
}
