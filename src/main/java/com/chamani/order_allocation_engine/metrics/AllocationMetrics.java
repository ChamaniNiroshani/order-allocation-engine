package com.chamani.order_allocation_engine.metrics;

import com.chamani.order_allocation_engine.concurrency.AllocationProperties;
import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.exception.StockContentionException;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.saga.CheckoutOutcomes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Every business meter of the allocation engine lives here, so names and tags are defined once.
 *
 * <ul>
 *   <li>{@code allocation.requests} {flow, outcome, strategy}: one count per request</li>
 *   <li>{@code allocation.latency} {flow, strategy}: request time, with a percentile histogram for p99 in Prometheus</li>
 *   <li>{@code saga.compensations} {reason}: orders given back (declined | timeout | expired)</li>
 *   <li>{@code saga.refunds}: refunds the saga asked the payment gateway for</li>
 *   <li>{@code reservations.active}: orders currently RESERVED (a cached value, see {@link ActiveReservationsRefresher})</li>
 * </ul>
 * ({@code allocation.lock.retries} stays where it is: OptimisticRetryExecutor counts it.)
 *
 * Tags are low-cardinality on purpose: a fixed handful of values each. Never an order id, item id or key.
 */
@Component
public class AllocationMetrics {

    public static final String REQUESTS = "allocation.requests";
    public static final String LATENCY = "allocation.latency";
    public static final String COMPENSATIONS = "saga.compensations";
    public static final String REFUNDS = "saga.refunds";
    public static final String ACTIVE_RESERVATIONS = "reservations.active";

    /** Which of the two entry points handled the request. */
    public enum Flow { DIRECT, CHECKOUT }

    /**
     * How a request ended. IN_PROGRESS is a replay that found the saga still running (HTTP 202); it is neither
     * a success nor a failure, so it gets its own value rather than distorting another one.
     */
    public enum Outcome { ALLOCATED, REJECTED, CONTENTION, ERROR, IN_PROGRESS }

    /** Values of the {@code reason} tag on saga.compensations. */
    public static final String REASON_DECLINED = "declined";
    public static final String REASON_TIMEOUT = "timeout";
    public static final String REASON_EXPIRED = "expired";

    /** Checkout does not use allocation.locking-mode: it reserves with one atomic UPDATE under the order's row lock. */
    static final String SAGA_STRATEGY = "saga";

    private static final Duration SLO_THRESHOLD = Duration.ofMillis(200);

    private final Map<Flow, Map<Outcome, Counter>> requests = new EnumMap<>(Flow.class);
    private final Map<Flow, Timer> latency = new EnumMap<>(Flow.class);
    private final Map<String, Counter> compensations;
    private final Counter refunds;
    private final AtomicLong activeReservations = new AtomicLong();

    public AllocationMetrics(MeterRegistry registry, AllocationProperties properties) {
        for (Flow flow : Flow.values()) {
            String strategy = strategyOf(flow, properties);
            Map<Outcome, Counter> byOutcome = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                // registered up front so every series exists (at 0) before its first event and rate() works
                byOutcome.put(outcome, Counter.builder(REQUESTS)
                        .description("Allocation requests by flow, outcome and locking strategy")
                        .tag("flow", tag(flow)).tag("outcome", tag(outcome)).tag("strategy", strategy)
                        .register(registry));
            }
            requests.put(flow, byOutcome);
            latency.put(flow, Timer.builder(LATENCY)
                    .description("Time to handle an allocation request, whatever its outcome")
                    .tag("flow", tag(flow)).tag("strategy", strategy)
                    .publishPercentileHistogram(true)               // histogram_quantile() needs the buckets
                    .serviceLevelObjectives(SLO_THRESHOLD)          // an exact le="0.2" bucket for the 200 ms SLO
                    .minimumExpectedValue(Duration.ofMillis(1))
                    .maximumExpectedValue(Duration.ofSeconds(10))
                    .register(registry));
        }
        compensations = Map.of(
                REASON_DECLINED, compensationCounter(registry, REASON_DECLINED),
                REASON_TIMEOUT, compensationCounter(registry, REASON_TIMEOUT),
                REASON_EXPIRED, compensationCounter(registry, REASON_EXPIRED));
        refunds = Counter.builder(REFUNDS)
                .description("Refunds the checkout saga asked the payment gateway for (timeouts and payments that arrived too late)")
                .register(registry);
        Gauge.builder(ACTIVE_RESERVATIONS, activeReservations, AtomicLong::get)
                .description("Orders currently RESERVED (stock held, payment not finished); refreshed periodically, not per scrape")
                .strongReference(true)
                .register(registry);
    }

    /** Runs one request, then counts its outcome and records its time. Exceptions are classified and rethrown. */
    public Order measure(Flow flow, Supplier<Order> request) {
        long start = System.nanoTime();
        Outcome outcome = Outcome.ERROR;
        try {
            Order order = request.get();
            outcome = outcomeOf(order);
            return order;
        } catch (StockContentionException e) {
            outcome = Outcome.CONTENTION;
            throw e;
        } catch (IdempotencyKeyReusedException | IllegalArgumentException e) {
            outcome = Outcome.REJECTED;                  // 422 / 404: the caller's mistake, not a failure of ours
            throw e;
        } finally {
            requests.get(flow).get(outcome).increment();
            latency.get(flow).record(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /** The same 2xx / 4xx / 5xx split the HTTP layer uses, so metrics and responses cannot disagree. */
    static Outcome outcomeOf(Order order) {
        HttpStatus status = CheckoutOutcomes.httpStatus(order);
        if (status == HttpStatus.ACCEPTED) {
            return Outcome.IN_PROGRESS;
        }
        if (status.is5xxServerError()) {
            return Outcome.ERROR;
        }
        return status.is2xxSuccessful() ? Outcome.ALLOCATED : Outcome.REJECTED;
    }

    public void compensation(String reason) {
        compensation(reason, 1);
    }

    public void compensation(String reason, int count) {
        Counter counter = compensations.get(reason);
        if (counter == null) {
            throw new IllegalArgumentException("Unknown compensation reason: " + reason);
        }
        if (count > 0) {
            counter.increment(count);
        }
    }

    public void refund() {
        refunds.increment();
    }

    public void setActiveReservations(long count) {
        activeReservations.set(count);
    }

    private static Counter compensationCounter(MeterRegistry registry, String reason) {
        return Counter.builder(COMPENSATIONS)
                .description("Orders the checkout saga gave back, by why")
                .tag("reason", reason)
                .register(registry);
    }

    private static String strategyOf(Flow flow, AllocationProperties properties) {
        return flow == Flow.DIRECT ? properties.lockingMode().name() : SAGA_STRATEGY;
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
