package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.saga.CheckoutOrchestrator;
import com.chamani.order_allocation_engine.saga.CheckoutOutcomes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Random declines and timeouts under concurrent load: whatever mix happens, the books must balance. */
@TestPropertySource(properties = {
        "payment.fake.failure-rate=0.3", "payment.fake.timeout-rate=0.1",
        "payment.fake.latency-ms=5", "payment.fake.timeout-ms=5"})
class SagaMixedFailuresScenarioTest extends AbstractSagaScenarioTest {

    @Autowired
    private CheckoutOrchestrator orchestrator;

    @Test
    void randomDeclinesAndTimeouts_underLoad_keepEveryInvariant() throws Exception {
        final int stock = 30;
        final int requests = 120;
        UUID itemId = newItem(stock);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Order>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            String key = newKey();
            futures.add(pool.submit(() -> {
                go.await();
                return orchestrator.checkout(itemId, 1, key);
            }));
        }
        go.countDown();
        List<Order> orders = new ArrayList<>();
        for (Future<Order> future : futures) {
            orders.add(future.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();

        Map<OrderStatus, Integer> byStatus = new EnumMap<>(OrderStatus.class);
        for (Order returned : orders) {
            Order order = orderRepository.findById(returned.getOrderId()).orElseThrow();
            String key = order.getIdempotencyKey();
            byStatus.merge(order.getStatus(), 1, Integer::sum);

            // every request ended in a finished state and a known HTTP answer
            assertTrue(Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, OrderStatus.FAILED).contains(order.getStatus()),
                    "unexpected final status " + order.getStatus());
            assertTrue(Set.of(HttpStatus.CREATED, HttpStatus.PAYMENT_REQUIRED, HttpStatus.CONFLICT, HttpStatus.BAD_GATEWAY)
                    .contains(CheckoutOutcomes.httpStatus(order)));

            switch (order.getStatus()) {
                case CONFIRMED -> {
                    assertEquals(1, gateway.chargeCount(key), "a confirmed order was charged exactly once");
                    assertEquals(0, gateway.refundCount(key), "and never refunded");
                }
                case CANCELLED -> assertEquals(gateway.chargeCount(key), gateway.refundCount(key),
                        "a cancelled order must have anything it was charged given back");
                case FAILED -> assertEquals(0, gateway.chargeCount(key), "no stock, so no charge");
                default -> { }
            }

            // the audit trail ends where the order ends
            List<String> actions = actions(order.getOrderId());
            assertTrue(actions.get(actions.size() - 1).endsWith("->" + order.getStatus()), actions.toString());
        }

        int confirmed = byStatus.getOrDefault(OrderStatus.CONFIRMED, 0);
        assertTrue(confirmed <= stock, "never more confirmed orders than stock: " + confirmed);
        assertStock(itemId, stock - confirmed, 0);                        // nothing may stay reserved
        assertTrue(byStatus.size() > 1, "the mix should have produced several outcomes: " + byStatus);
    }
}
