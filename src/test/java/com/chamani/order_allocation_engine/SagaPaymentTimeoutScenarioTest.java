package com.chamani.order_allocation_engine;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The orchestrator's own limit on a payment call (payment.timeout), separate from the fake's simulated timeout.
 * The gateway here is merely SLOW (3 s, it never reports a timeout itself); the saga must give up after 300 ms and take
 * the same path as a gateway timeout: refund by key, cancel the order, release the stock, answer 502.
 */
@TestPropertySource(properties = {"payment.fake.latency-ms=3000", "payment.timeout=300ms"})
class SagaPaymentTimeoutScenarioTest extends AbstractSagaScenarioTest {

    @Test
    void aSlowGateway_isGivenUpOn_refunded_cancelled_andTheStockIsRestored() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        double timeouts = counter("saga.compensations", "reason", "timeout");
        double refunds = counter("saga.refunds");

        long started = System.nanoTime();
        checkout(itemId, 4, key)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.reason").value("Payment timed out"));
        long tookMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(tookMs < 2_500, "the saga must stop waiting at payment.timeout (300 ms), not at the gateway's 3 s; took " + tookMs + " ms");
        var order = order(key);
        assertEquals("CANCELLED", order.getStatus().name());
        assertStock(itemId, 10, 0);                                                      // exactly as before
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->CANCELLED"), actions(order.getOrderId()));
        assertEquals(1, gateway.refundCallCount(key), "an unanswered charge must be refunded by key");
        assertEquals(gateway.chargeCount(key), gateway.refundCount(key), "whatever was charged was given back");
        assertEquals(timeouts + 1, counter("saga.compensations", "reason", "timeout"));
        assertEquals(refunds + 1, counter("saga.refunds"));
    }

    @Test
    void retryOfAGivenUpCheckout_returnsTheSame502_withoutChargingOrRefundingAgain() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();

        checkout(itemId, 4, key).andExpect(status().isBadGateway());
        checkout(itemId, 4, key).andExpect(status().isBadGateway());

        assertEquals(1, ordersWithKey(key));
        assertEquals(1, gateway.chargeCount(key));
        assertEquals(1, gateway.refundCallCount(key), "the replay must not refund again");
        assertStock(itemId, 10, 0);
    }
}
