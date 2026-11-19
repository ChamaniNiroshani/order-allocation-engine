package com.chamani.order_allocation_engine;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every charge times out (payment.fake.timeout-rate=1.0). The fake models the worst case: the money WAS taken
 * and only the answer was lost, so a correct saga must refund it.
 */
@TestPropertySource(properties = {"payment.fake.timeout-rate=1.0", "payment.fake.timeout-ms=0", "payment.fake.latency-ms=0"})
class SagaTimeoutScenarioTest extends AbstractSagaScenarioTest {

    @Test
    void timeout_returns502_refunds_cancelsTheOrder_andRestoresTheStock() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();

        checkout(itemId, 4, key)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.reason").value("Payment timed out"));

        var order = order(key);
        assertEquals("CANCELLED", order.getStatus().name());
        assertStock(itemId, 10, 0);                                                    // exactly as before
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->CANCELLED"), actions(order.getOrderId()));
        assertEquals(1, gateway.refundCallCount(key), "the saga must call refund after a timeout");
        assertEquals(gateway.chargeCount(key), gateway.refundCount(key), "whatever was charged was given back");
    }

    @Test
    void retryOfATimedOutCheckout_returnsTheSame502_withoutChargingOrRefundingAgain() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();

        checkout(itemId, 4, key).andExpect(status().isBadGateway());
        checkout(itemId, 4, key).andExpect(status().isBadGateway());

        assertEquals(1, ordersWithKey(key));
        assertEquals(1, gateway.chargeCount(key));
        assertEquals(1, gateway.refundCallCount(key), "the replay must not refund again");
        assertEquals(1, gateway.refundCount(key));
        assertStock(itemId, 10, 0);
    }
}
