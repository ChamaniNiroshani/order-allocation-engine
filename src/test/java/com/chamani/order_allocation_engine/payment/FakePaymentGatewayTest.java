package com.chamani.order_allocation_engine.payment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit tests: no Spring context, no sleeping (latency and timeout are tiny or zero). */
class FakePaymentGatewayTest {

    private static final BigDecimal AMOUNT = new BigDecimal("25.00");

    private static FakePaymentGateway gateway(double failureRate, long latencyMs, double timeoutRate, long timeoutMs, long seed) {
        return new FakePaymentGateway(new FakePaymentProperties(failureRate, latencyMs, timeoutRate, timeoutMs), new Random(seed));
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    @Test
    void approves_whenNothingIsConfiguredToFail() {
        FakePaymentGateway gw = gateway(0, 0, 0, 0, 1);
        String key = key();

        PaymentResult result = gw.charge(UUID.randomUUID(), AMOUNT, key);

        assertTrue(result.isApproved());
        assertNotNull(result.paymentId());
        assertEquals(1, gw.chargeCount(key));
    }

    @Test
    void declines_whenFailureRateIsOne_andChargesNothing() {
        FakePaymentGateway gw = gateway(1.0, 0, 0, 0, 1);
        String key = key();

        PaymentResult result = gw.charge(UUID.randomUUID(), AMOUNT, key);

        assertEquals(PaymentResult.Status.DECLINED, result.status());
        assertEquals("Card declined", result.reason());
        assertEquals(0, gw.chargeCount(key));
    }

    @Test
    void sameKey_returnsTheSameResult_andChargesOnce() {
        FakePaymentGateway gw = gateway(0, 0, 0, 0, 1);
        String key = key();

        PaymentResult first = gw.charge(UUID.randomUUID(), AMOUNT, key);
        PaymentResult second = gw.charge(UUID.randomUUID(), AMOUNT, key);
        PaymentResult third = gw.charge(UUID.randomUUID(), AMOUNT, key);

        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(1, gw.chargeCount(key), "the key must never be charged twice");
    }

    @Test
    void sameKey_declinedOnce_isDeclinedOnEveryRetry() {
        FakePaymentGateway gw = gateway(1.0, 0, 0, 0, 1);
        String key = key();

        assertEquals(gw.charge(UUID.randomUUID(), AMOUNT, key), gw.charge(UUID.randomUUID(), AMOUNT, key));
        assertEquals(0, gw.chargeCount(key));
    }

    @Test
    void sameKey_fromManyThreadsAtOnce_chargesOnce() throws Exception {
        FakePaymentGateway gw = gateway(0, 5, 0, 0, 1);
        String key = key();
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        Set<String> paymentIds = ConcurrentHashMap.newKeySet();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                paymentIds.add(gw.charge(UUID.randomUUID(), AMOUNT, key).paymentId());
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(1, gw.chargeCount(key));
        assertEquals(1, paymentIds.size(), "every caller gets the same payment");
    }

    @Test
    void timeout_throws_butTheChargeHappened_andARetryReturnsItWithoutChargingAgain() {
        FakePaymentGateway gw = gateway(0, 0, 1.0, 5, 1);
        String key = key();

        assertThrows(PaymentTimeoutException.class, () -> gw.charge(UUID.randomUUID(), AMOUNT, key));
        assertEquals(1, gw.chargeCount(key), "worst case: the money was taken, the answer was lost");

        PaymentResult retry = gw.charge(UUID.randomUUID(), AMOUNT, key);
        assertTrue(retry.isApproved());
        assertEquals(1, gw.chargeCount(key), "the retry must not charge again");
    }

    @Test
    void timeout_blocksForTimeoutMs() {
        FakePaymentGateway gw = gateway(0, 0, 1.0, 80, 1);

        long start = System.nanoTime();
        assertThrows(PaymentTimeoutException.class, () -> gw.charge(UUID.randomUUID(), AMOUNT, key()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 70, "expected about 80 ms, was " + elapsedMs);
    }

    @Test
    void latency_isApplied() {
        FakePaymentGateway gw = gateway(0, 80, 0, 0, 1);

        long start = System.nanoTime();
        gw.charge(UUID.randomUUID(), AMOUNT, key());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs >= 70, "expected about 80 ms, was " + elapsedMs);
    }

    @Test
    void refund_ofAChargedKey_appliesOnce_evenIfCalledTwice() {
        FakePaymentGateway gw = gateway(0, 0, 0, 0, 1);
        String key = key();
        gw.charge(UUID.randomUUID(), AMOUNT, key);

        gw.refund(key);
        gw.refund(key);

        assertEquals(1, gw.refundCount(key));
        assertEquals(2, gw.refundCallCount(key));
    }

    @Test
    void refund_afterATimeout_returnsTheMoney() {
        FakePaymentGateway gw = gateway(0, 0, 1.0, 1, 1);
        String key = key();
        assertThrows(PaymentTimeoutException.class, () -> gw.charge(UUID.randomUUID(), AMOUNT, key));

        gw.refund(key);

        assertEquals(1, gw.refundCount(key));
    }

    @Test
    void refund_ofAKeyThatWasNeverCharged_isANoOp() {
        FakePaymentGateway gw = gateway(0, 0, 0, 0, 1);
        String key = key();

        gw.refund(key);   // must not throw

        assertEquals(0, gw.refundCount(key));
        assertEquals(0, gw.chargeCount(key));
    }

    @Test
    void refund_ofADeclinedKey_isANoOp() {
        FakePaymentGateway gw = gateway(1.0, 0, 0, 0, 1);
        String key = key();
        gw.charge(UUID.randomUUID(), AMOUNT, key);

        gw.refund(key);

        assertEquals(0, gw.refundCount(key), "nothing was charged, so nothing is refunded");
    }

    @Test
    void sameSeed_givesTheSameSequenceOfOutcomes() {
        List<String> a = outcomes(gateway(0.5, 0, 0, 0, 42));
        List<String> b = outcomes(gateway(0.5, 0, 0, 0, 42));

        assertEquals(a, b);
        assertTrue(a.contains("APPROVED") && a.contains("DECLINED"), "a 50% rate should mix outcomes: " + a);
    }

    private static List<String> outcomes(FakePaymentGateway gw) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            out.add(gw.charge(UUID.randomUUID(), AMOUNT, "k" + i).status().name());
        }
        return out;
    }

    @Test
    void rejectsAZeroOrNegativeAmount() {
        FakePaymentGateway gw = gateway(0, 0, 0, 0, 1);

        assertThrows(IllegalArgumentException.class, () -> gw.charge(UUID.randomUUID(), BigDecimal.ZERO, key()));
        assertThrows(IllegalArgumentException.class, () -> gw.charge(UUID.randomUUID(), new BigDecimal("-1"), key()));
    }

    @Test
    void reset_forgetsEveryKey() {
        FakePaymentGateway gw = gateway(0, 0, 0, 0, 1);
        String key = key();
        gw.charge(UUID.randomUUID(), AMOUNT, key);

        gw.reset();

        assertEquals(0, gw.chargeCount(key));
    }

    // ---- properties -----------------------------------------------------------------------------------

    @Test
    void properties_haveTheDocumentedDefaults() {
        FakePaymentProperties p = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("payment.fake", FakePaymentProperties.class);

        assertEquals(0.0, p.failureRate());
        assertEquals(50, p.latencyMs());
        assertEquals(0.0, p.timeoutRate());
        assertEquals(2000, p.timeoutMs());
    }

    @Test
    void properties_bindFromKebabCaseKeys() {
        Map<String, String> source = new HashMap<>();
        source.put("payment.fake.failure-rate", "0.25");
        source.put("payment.fake.latency-ms", "10");
        source.put("payment.fake.timeout-rate", "0.1");
        source.put("payment.fake.timeout-ms", "500");

        FakePaymentProperties p = new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("payment.fake", FakePaymentProperties.class);

        assertEquals(0.25, p.failureRate());
        assertEquals(10, p.latencyMs());
        assertEquals(0.1, p.timeoutRate());
        assertEquals(500, p.timeoutMs());
    }

    @Test
    void properties_rejectRatesOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class, () -> new FakePaymentProperties(1.5, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FakePaymentProperties(0, 0, -0.1, 0));
        assertThrows(IllegalArgumentException.class, () -> new FakePaymentProperties(0, -1, 0, 0));
    }
}
