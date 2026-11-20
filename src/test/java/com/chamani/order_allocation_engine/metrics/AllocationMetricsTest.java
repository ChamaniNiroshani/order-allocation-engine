package com.chamani.order_allocation_engine.metrics;

import com.chamani.order_allocation_engine.concurrency.AllocationProperties;
import com.chamani.order_allocation_engine.concurrency.LockingMode;
import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.exception.StockContentionException;
import com.chamani.order_allocation_engine.metrics.AllocationMetrics.Flow;
import com.chamani.order_allocation_engine.metrics.AllocationMetrics.Outcome;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.saga.CheckoutOutcomes;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.distribution.CountAtBucket;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocationMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final AllocationMetrics metrics = new AllocationMetrics(registry, new AllocationProperties(LockingMode.OPTIMISTIC));

    private double requests(Flow flow, Outcome outcome) {
        return registry.get(AllocationMetrics.REQUESTS)
                .tag("flow", flow.name().toLowerCase()).tag("outcome", outcome.name().toLowerCase())
                .counter().count();
    }

    private static Order order() {
        return new Order(UUID.randomUUID(), 1, "k-" + UUID.randomUUID(), "hash");
    }

    // ---- outcome mapping: the same split as the HTTP status ----------------------------------------------

    @Test
    void statuses_mapToTheOutcomeTheirHttpStatusImplies() {
        Order allocated = order();
        allocated.markAllocated();
        Order failed = order();
        failed.markFailed(CheckoutOutcomes.REASON_INSUFFICIENT);
        Order confirmed = order();
        confirmed.reserve();
        confirmed.markPaid("p");
        confirmed.confirm();
        Order declined = order();
        declined.reserve();
        declined.cancel(CheckoutOutcomes.REASON_DECLINED + ": card");
        Order timedOut = order();
        timedOut.reserve();
        timedOut.cancel(CheckoutOutcomes.REASON_TIMEOUT);
        Order expired = order();
        expired.reserve();
        expired.expire();
        Order inFlight = order();
        inFlight.reserve();

        assertEquals(Outcome.ALLOCATED, AllocationMetrics.outcomeOf(allocated));
        assertEquals(Outcome.ALLOCATED, AllocationMetrics.outcomeOf(confirmed));
        assertEquals(Outcome.REJECTED, AllocationMetrics.outcomeOf(failed), "409 no stock is a correct answer");
        assertEquals(Outcome.REJECTED, AllocationMetrics.outcomeOf(declined), "402 declined is a correct answer");
        assertEquals(Outcome.REJECTED, AllocationMetrics.outcomeOf(expired));
        assertEquals(Outcome.ERROR, AllocationMetrics.outcomeOf(timedOut), "502 is the one saga result that is a failure");
        assertEquals(Outcome.IN_PROGRESS, AllocationMetrics.outcomeOf(inFlight));
    }

    // ---- measure(): counts exactly once, classifies exceptions, always records the time --------------------

    @Test
    void measure_countsTheReturnedOrdersOutcome_andTheTime() {
        Order allocated = order();
        allocated.markAllocated();

        assertEquals(allocated, metrics.measure(Flow.DIRECT, () -> allocated));

        assertEquals(1, requests(Flow.DIRECT, Outcome.ALLOCATED));
        assertEquals(0, requests(Flow.CHECKOUT, Outcome.ALLOCATED), "the other flow is untouched");
        assertEquals(1, registry.get(AllocationMetrics.LATENCY).tag("flow", "direct").timer().count());
    }

    @Test
    void measure_classifiesExceptions_countsThem_andRethrows() {
        assertThrows(StockContentionException.class, () -> metrics.measure(Flow.DIRECT, () -> {
            throw new StockContentionException(5, 1, new RuntimeException());
        }));
        assertThrows(IdempotencyKeyReusedException.class, () -> metrics.measure(Flow.DIRECT, () -> {
            throw new IdempotencyKeyReusedException("k");
        }));
        assertThrows(IllegalArgumentException.class, () -> metrics.measure(Flow.CHECKOUT, () -> {
            throw new IllegalArgumentException("Item not found");
        }));
        assertThrows(IllegalStateException.class, () -> metrics.measure(Flow.CHECKOUT, () -> {
            throw new IllegalStateException("boom");
        }));

        assertEquals(1, requests(Flow.DIRECT, Outcome.CONTENTION));
        assertEquals(1, requests(Flow.DIRECT, Outcome.REJECTED), "a reused key is the caller's mistake");
        assertEquals(1, requests(Flow.CHECKOUT, Outcome.REJECTED), "an unknown item is the caller's mistake");
        assertEquals(1, requests(Flow.CHECKOUT, Outcome.ERROR), "anything unexpected is an error");
        assertEquals(2, registry.get(AllocationMetrics.LATENCY).tag("flow", "direct").timer().count(), "failed requests are timed too");
    }

    // ---- the meters' shape --------------------------------------------------------------------------------

    @Test
    void strategyTag_isTheLockingModeForTheDirectFlow_andSagaForCheckout() {
        assertEquals("OPTIMISTIC", registry.get(AllocationMetrics.LATENCY).tag("flow", "direct").timer().getId().getTag("strategy"));
        assertEquals("saga", registry.get(AllocationMetrics.LATENCY).tag("flow", "checkout").timer().getId().getTag("strategy"));
    }

    @Test
    void latencyTimer_publishesAHistogram_withAnExact200msBucket() {
        HistogramSnapshot snapshot = registry.get(AllocationMetrics.LATENCY).tag("flow", "direct").timer().takeSnapshot();
        assertTrue(snapshot.histogramCounts().length > 0, "no histogram buckets: histogram_quantile() would have nothing to work with");
        assertTrue(Arrays.stream(snapshot.histogramCounts()).map(CountAtBucket::bucket)
                        .anyMatch(bucketNanos -> bucketNanos == TimeUnit.MILLISECONDS.toNanos(200)),
                "the SLO threshold needs its own bucket");
    }

    @Test
    void everySeriesExistsFromTheStart_soRateWorksBeforeTheFirstEvent() {
        assertEquals(Flow.values().length * Outcome.values().length, registry.find(AllocationMetrics.REQUESTS).counters().size());
        assertEquals(3, registry.find(AllocationMetrics.COMPENSATIONS).counters().size());
        assertEquals(0, registry.get(AllocationMetrics.REFUNDS).counter().count());
        assertEquals(0, registry.get(AllocationMetrics.ACTIVE_RESERVATIONS).gauge().value());
    }

    @Test
    void tags_areLowCardinality_neverAnIdentifier() {
        Set<String> allowedKeys = Set.of("flow", "outcome", "strategy", "reason");
        long businessMeters = 0;
        for (Meter meter : registry.getMeters()) {
            boolean bucketGauge = meter.getId().getName().endsWith(".histogram");   // one per bucket: tag le, a fixed set of values
            if (!bucketGauge) {
                businessMeters++;
            }
            for (Tag tag : meter.getId().getTags()) {
                assertTrue(allowedKeys.contains(tag.getKey()) || (bucketGauge && tag.getKey().equals("le")),
                        "unexpected tag " + tag + " on " + meter.getId().getName());
                assertTrue(tag.getValue().length() < 20 && !tag.getValue().contains("-"),
                        "tag value looks like an identifier: " + tag);
            }
        }
        // the whole business surface is a fixed, small number of series: 10 counters, 2 timers, 3 compensations, refunds, gauge
        assertEquals(10 + 2 + 3 + 1 + 1, businessMeters);
    }

    // ---- saga counters ------------------------------------------------------------------------------------

    @Test
    void compensations_areCountedByReason() {
        metrics.compensation(AllocationMetrics.REASON_DECLINED);
        metrics.compensation(AllocationMetrics.REASON_EXPIRED, 3);
        metrics.compensation(AllocationMetrics.REASON_EXPIRED, 0);

        assertEquals(1, registry.get(AllocationMetrics.COMPENSATIONS).tag("reason", "declined").counter().count());
        assertEquals(3, registry.get(AllocationMetrics.COMPENSATIONS).tag("reason", "expired").counter().count());
        assertEquals(0, registry.get(AllocationMetrics.COMPENSATIONS).tag("reason", "timeout").counter().count());
        assertThrows(IllegalArgumentException.class, () -> metrics.compensation("some-new-reason"));
    }

    @Test
    void refunds_andTheActiveReservationsGauge() {
        metrics.refund();
        metrics.refund();
        metrics.setActiveReservations(7);

        assertEquals(2, registry.get(AllocationMetrics.REFUNDS).counter().count());
        assertEquals(7, registry.get(AllocationMetrics.ACTIVE_RESERVATIONS).gauge().value());
    }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void outcomeTagValues_areLowerCaseWords(Outcome outcome) {
        assertEquals(outcome.name().toLowerCase(), registry.get(AllocationMetrics.REQUESTS)
                .tag("flow", "direct").tag("outcome", outcome.name().toLowerCase()).counter().getId().getTag("outcome"));
    }
}
