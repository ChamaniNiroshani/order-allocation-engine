package com.chamani.order_allocation_engine.exception;

import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Logs one WARN line (no stack trace) at most once per interval and counts the ones it skipped. During an outage every
 * request fails the same way; one line per request would bury the log (the chaos run wrote 2,847 stack-trace lines for
 * just 9 failures), while one line per interval with a count says everything.
 */
final class RateLimitedWarn {

    private final long intervalNanos;
    private final AtomicLong nextAllowed;
    private final LongAdder suppressed = new LongAdder();

    RateLimitedWarn(long intervalMillis) {
        this.intervalNanos = intervalMillis * 1_000_000L;
        this.nextAllowed = new AtomicLong(System.nanoTime());
    }

    /** Logs {@code message} (an SLF4J pattern with {@code args}) unless a line was already logged in this interval. */
    void warn(Logger log, String message, Object... args) {
        long now = System.nanoTime();
        long allowedAt = nextAllowed.get();
        if (now - allowedAt >= 0 && nextAllowed.compareAndSet(allowedAt, now + intervalNanos)) {
            long skipped = suppressed.sumThenReset();
            if (skipped > 0) {
                log.warn(message + " ({} similar failures were not logged in the last {} s)",
                        append(args, skipped, intervalNanos / 1_000_000_000L));
            } else {
                log.warn(message, args);
            }
        } else {
            suppressed.increment();
        }
    }

    private static Object[] append(Object[] args, Object... more) {
        Object[] all = new Object[args.length + more.length];
        System.arraycopy(args, 0, all, 0, args.length);
        System.arraycopy(more, 0, all, args.length, more.length);
        return all;
    }
}
