package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.payment.FakePaymentGateway;
import com.chamani.order_allocation_engine.saga.CheckoutSteps;
import com.chamani.order_allocation_engine.saga.ReservationExpiryJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A healthy but slow gateway (200 ms per charge), so requests overlap: retries during a saga, reservations
 * that are abandoned, and an order the expiry job takes while its payment is in flight.
 */
@TestPropertySource(properties = {"payment.fake.latency-ms=200"})
class SagaConcurrencyScenarioTest extends AbstractSagaScenarioTest {

    @Autowired
    private CheckoutSteps steps;

    @Autowired
    private ReservationExpiryJob job;

    @MockitoSpyBean
    private FakePaymentGateway spiedGateway;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ---- same key retried ---------------------------------------------------------------------------------

    @Test
    void sameKeyRetriedSequentially_oneReservation_oneCharge() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();

        checkout(itemId, 3, key).andExpect(status().isCreated());
        checkout(itemId, 3, key).andExpect(status().isCreated());
        checkout(itemId, 3, key).andExpect(status().isCreated());

        assertEquals(1, ordersWithKey(key));
        assertEquals(1, gateway.chargeCount(key));
        assertStock(itemId, 7, 0);
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->PAID", "PAID->CONFIRMED"), actions(order(key).getOrderId()));
    }

    @ParameterizedTest(name = "{0} threads, same key, at the same moment")
    @ValueSource(ints = {2, 10})
    void sameKeyFromSeveralThreadsAtOnce_oneReservation_atMostOneCharge(int threads) throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<String>> responses = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            responses.add(pool.submit(() -> {
                go.await();
                var result = checkout(itemId, 3, key).andReturn().getResponse();
                assertTrue(result.getStatus() == 201 || result.getStatus() == 202,
                        "a retry during the saga is either the finished order or 'still in flight', got " + result.getStatus());
                return objectMapper.readTree(result.getContentAsString()).get("orderId").asText();
            }));
        }
        go.countDown();
        Set<String> orderIds = new HashSet<>();
        for (Future<String> response : responses) {
            orderIds.add(response.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(1, orderIds.size(), "every caller must see the same order");
        assertEquals(1, ordersWithKey(key), "one reservation");
        assertEquals(1, gateway.chargeCount(key), "at most one charge");
        assertEquals(OrderStatus.CONFIRMED, order(key).getStatus());
        assertStock(itemId, 7, 0);
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->PAID", "PAID->CONFIRMED"), actions(order(key).getOrderId()),
                "the transitions are written once, not once per caller");
    }

    // ---- crashes and the expiry job ---------------------------------------------------------------------

    @Test
    void abandonedReservation_isExpiredByTheJob_andTheStockComesBack() {
        UUID itemId = newItem(10);
        String key = newKey();
        // Simulated crash: the reservation was made, then the process died before charging.
        UUID orderId = steps.reserve(itemId, 4, key, "hash").order().getOrderId();
        assertStock(itemId, 6, 4);
        jdbc.update("UPDATE orders SET reserved_at = now() - interval '10 minutes' WHERE order_id = ?", orderId);

        double expired = counter("saga.compensations", "reason", "expired");

        job.expireStale();

        assertEquals(expired + 1, counter("saga.compensations", "reason", "expired"), "the job counts what it expired");
        assertEquals(OrderStatus.EXPIRED, order(key).getStatus());
        assertStock(itemId, 10, 0);
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->EXPIRED"), actions(orderId));
        assertEquals(0, gateway.chargeCount(key), "nothing was ever charged");
    }

    @Test
    void orderExpiredByTheJobWhilePaymentIsInFlight_isRefunded_andNoStockIsConsumedTwice() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        // While the charge is in flight, the real expiry job runs. It can: the saga holds no transaction and no lock then.
        doAnswer(invocation -> {
            jdbc.update("UPDATE orders SET reserved_at = now() - interval '10 minutes' WHERE idempotency_key = ?", key);
            assertEquals(1, job.expireStale(), "the job must be able to take the order while the payment is in flight");
            return invocation.callRealMethod();                       // the payment itself still succeeds
        }).when(spiedGateway).charge(any(), any(), eq(key));

        double expired = counter("saga.compensations", "reason", "expired");
        double refunds = counter("saga.refunds");
        double cancelledCompensations = counter("saga.compensations", "reason", "declined") + counter("saga.compensations", "reason", "timeout");

        checkout(itemId, 4, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        assertEquals(expired + 1, counter("saga.compensations", "reason", "expired"), "counted once, by the job");
        assertEquals(refunds + 1, counter("saga.refunds"), "the late payment was refunded");
        assertEquals(cancelledCompensations, counter("saga.compensations", "reason", "declined") + counter("saga.compensations", "reason", "timeout"),
                "the saga must not count a compensation the job already made");

        var order = order(key);
        assertEquals(OrderStatus.EXPIRED, order.getStatus());
        assertTrue(order.getFailureReason().contains("payment refunded"), order.getFailureReason());
        assertEquals(1, spiedGateway.chargeCount(key), "the money was taken");
        assertEquals(1, spiedGateway.refundCount(key), "and given back");
        assertStock(itemId, 10, 0);                                    // released once, never confirmed
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->EXPIRED", "PAYMENT_REFUNDED"), actions(order.getOrderId()));
    }

    @Test
    void crashAfterTheChargeBeforeTheConfirm_isFinishedByTheJob() {
        UUID itemId = newItem(10);
        String key = newKey();
        UUID orderId = steps.reserve(itemId, 4, key, "hash").order().getOrderId();
        steps.recordPayment(orderId, "pay-crashed");                  // charged and recorded, then the process died
        jdbc.update("UPDATE orders SET reserved_at = now() - interval '10 minutes' WHERE order_id = ?", orderId);

        job.recoverPaid();

        assertEquals(OrderStatus.CONFIRMED, order(key).getStatus());
        assertStock(itemId, 6, 0);
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->PAID", "PAID->CONFIRMED"), actions(orderId));
    }
}
