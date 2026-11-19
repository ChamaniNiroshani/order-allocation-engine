package com.chamani.order_allocation_engine;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The gateway declines every charge (payment.fake.failure-rate=1.0). */
@TestPropertySource(properties = {"payment.fake.failure-rate=1.0", "payment.fake.latency-ms=0"})
class SagaDeclinedScenarioTest extends AbstractSagaScenarioTest {

    @Test
    void declined_returns402_cancelsTheOrder_restoresTheStock_andNothingIsCharged() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        double declined = counter("saga.compensations", "reason", "declined");
        double refunds = counter("saga.refunds");
        double rejected = counter("allocation.requests", "flow", "checkout", "outcome", "rejected");

        try (LogCapture logs = new LogCapture()) {
            checkout(itemId, 4, key)
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));

            assertEquals(java.util.List.of("order.compensated"), logs.eventNames(), "a decline is compensated, and nothing was charged to refund");
            var event = logs.events("order.compensated").get(0);
            assertEquals("declined", LogCapture.field(event, "why"));
            assertEquals(order(key).getOrderId().toString(), LogCapture.mdc(event).get("orderId"), "the order id is in the log context");
        }

        assertEquals(declined + 1, counter("saga.compensations", "reason", "declined"), "one compensation, for a decline");
        assertEquals(refunds, counter("saga.refunds"), "nothing was charged, so nothing is refunded");
        assertEquals(rejected + 1, counter("allocation.requests", "flow", "checkout", "outcome", "rejected"), "402 is a correct answer, not an error");

        var order = order(key);
        assertEquals("CANCELLED", order.getStatus().name());
        assertStock(itemId, 10, 0);                                                    // exactly as before
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->CANCELLED"), actions(order.getOrderId()));
        assertEquals(0, gateway.chargeCount(key), "a declined payment charges nothing");
        assertEquals(0, gateway.refundCallCount(key), "and there is nothing to refund");
    }

    @Test
    void retryOfADeclinedCheckout_returnsTheSameAnswer_withoutReservingAgain() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();

        double declined = counter("saga.compensations", "reason", "declined");

        checkout(itemId, 4, key).andExpect(status().isPaymentRequired());
        checkout(itemId, 4, key).andExpect(status().isPaymentRequired());

        assertEquals(declined + 1, counter("saga.compensations", "reason", "declined"), "the replay compensates nothing");
        assertEquals(1, ordersWithKey(key));
        assertEquals(2, actions(order(key).getOrderId()).size(), "the retry must not write another transition");
        assertStock(itemId, 10, 0);
    }

    @Test
    void manyConcurrentDeclines_leaveTheStockUntouched() throws Exception {
        UUID itemId = newItem(10);
        int requests = 20;                                   // 20 x 1 against 10 units: reservations overlap and compete
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String key = newKey();
            results.add(pool.submit(() -> {
                go.await();
                return checkout(itemId, 1, key).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> result : results) {
            int httpStatus = result.get(60, TimeUnit.SECONDS);
            // 402 when it got its reservation, 409 when others held the stock at that moment
            assertEquals(true, httpStatus == 402 || httpStatus == 409, "unexpected status " + httpStatus);
        }
        pool.shutdown();

        assertStock(itemId, 10, 0);
    }
}
