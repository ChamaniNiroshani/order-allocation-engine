package com.chamani.order_allocation_engine.concurrency;

import com.chamani.order_allocation_engine.exception.StockContentionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptimisticRetryExecutorTest {

    // base delay 0: no sleeping in unit tests
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OptimisticRetryExecutor executor = new OptimisticRetryExecutor(5, 0, registry);

    private static ObjectOptimisticLockingFailureException conflict() {
        return new ObjectOptimisticLockingFailureException("InventoryItem", "id");
    }

    @Test
    void succeedsFirstTime_noRetries() {
        assertEquals("ok", executor.execute(() -> "ok"));
        assertEquals(0, executor.retryCount());
    }

    @Test
    void retriesUntilItSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = executor.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw conflict();
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(3, calls.get());
        assertEquals(2, executor.retryCount());
        assertEquals(2.0, registry.get("allocation.lock.retries").counter().count(), "exposed as a Micrometer counter");
        assertEquals(0, executor.exhaustedCount());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();

        StockContentionException e = assertThrows(StockContentionException.class, () -> executor.execute(() -> {
            calls.incrementAndGet();
            throw conflict();
        }));

        assertEquals(5, calls.get(), "exactly max attempts");
        assertEquals(4, executor.retryCount());
        assertEquals(1, executor.exhaustedCount());
        assertEquals(1, e.getRetryAfterSeconds());
    }

    @Test
    void otherExceptions_arePropagatedWithoutRetry() {
        AtomicInteger calls = new AtomicInteger();
        IllegalArgumentException boom = new IllegalArgumentException("boom");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> executor.execute(() -> {
            calls.incrementAndGet();
            throw boom;
        }));

        assertSame(boom, thrown);
        assertEquals(1, calls.get());
    }
}
