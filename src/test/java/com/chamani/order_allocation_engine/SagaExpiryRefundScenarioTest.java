package com.chamani.order_allocation_engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.payment.FakePaymentGateway;
import com.chamani.order_allocation_engine.saga.CheckoutSteps;
import com.chamani.order_allocation_engine.saga.ReservationExpiryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** The expiry job gives the money back: a reservation can expire after its payment was already taken. */
class SagaExpiryRefundScenarioTest extends AbstractSagaScenarioTest {

    @Autowired
    private CheckoutSteps steps;

    @Autowired
    private ReservationExpiryJob job;

    @MockitoSpyBean
    private FakePaymentGateway spiedGateway;

    /** Nothing stale may be left over, so each run of the job touches only this test's order. */
    @BeforeEach
    void drainLeftovers() {
        job.expireStale();
        job.recoverPaid();
    }

    private UUID reserveStale(UUID itemId, String key) {
        UUID orderId = steps.reserve(itemId, 4, key, "hash").order().getOrderId();
        jdbc.update("UPDATE orders SET reserved_at = now() - interval '10 minutes' WHERE order_id = ?", orderId);
        return orderId;
    }

    @Test
    void crashAfterTheCharge_theExpiryReleasesTheStock_andRefundsExactlyOnce() {
        UUID itemId = newItem(10);
        String key = newKey();
        UUID orderId = reserveStale(itemId, key);
        // Charged, then the process died before the payment was recorded: the order is still RESERVED.
        assertTrue(spiedGateway.charge(orderId, BigDecimal.TEN, key).isApproved());
        assertEquals(OrderStatus.RESERVED, order(key).getStatus());
        assertStock(itemId, 6, 4);

        AtomicBoolean inTransaction = new AtomicBoolean(true);
        doAnswer(invocation -> {
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(OrderStatus.EXPIRED, order(key).getStatus(), "the expiry is committed before the refund call");
            return invocation.callRealMethod();
        }).when(spiedGateway).refund(key);
        double refunds = counter("saga.refunds");
        double failures = counter("saga.refund.failures");

        job.expireStale();
        job.expireStale();                                              // a second run must not refund again

        assertEquals(OrderStatus.EXPIRED, order(key).getStatus());
        assertStock(itemId, 10, 0);
        verify(spiedGateway, times(1)).refund(key);
        assertFalse(inTransaction.get(), "no transaction may be open during the gateway call");
        assertEquals(1, spiedGateway.chargeCount(key));
        assertEquals(1, spiedGateway.refundCount(key), "exactly one refund");
        assertEquals(refunds + 1, counter("saga.refunds"));
        assertEquals(failures, counter("saga.refund.failures"));
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->EXPIRED", "REFUND_REQUESTED"), actions(orderId));
    }

    @Test
    void neverChargedOrder_isStillRefunded_whichIsANoOp() {
        UUID itemId = newItem(10);
        String key = newKey();
        UUID orderId = reserveStale(itemId, key);
        double failures = counter("saga.refund.failures");

        assertEquals(1, job.expireStale());

        verify(spiedGateway, times(1)).refund(key);
        assertEquals(0, spiedGateway.chargeCount(key), "nothing was charged");
        assertEquals(0, spiedGateway.refundCount(key), "so nothing was paid back");
        assertEquals(failures, counter("saga.refund.failures"));
        assertEquals(OrderStatus.EXPIRED, order(key).getStatus());
        assertEquals("Reservation expired", order(key).getFailureReason(), "the order must not claim a refund");
        assertStock(itemId, 10, 0);
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->EXPIRED", "REFUND_REQUESTED"), actions(orderId));
    }

    @Test
    void failedRefund_keepsTheOrderExpired_countsAndWarns_andTheOtherRefundsStillRun() {
        UUID itemId = newItem(10);
        String failingKey = newKey();
        String otherKey = newKey();
        UUID failing = reserveStale(itemId, failingKey);
        UUID other = reserveStale(itemId, otherKey);
        jdbc.update("UPDATE orders SET reserved_at = now() - interval '20 minutes' WHERE order_id = ?", failing);   // refunded first
        doThrow(new IllegalStateException("gateway down for " + failingKey)).when(spiedGateway).refund(failingKey);
        double refunds = counter("saga.refunds");
        double failures = counter("saga.refund.failures");

        Logger jobLogger = (Logger) LoggerFactory.getLogger(ReservationExpiryJob.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        jobLogger.addAppender(logs);
        try {
            assertEquals(2, job.expireStale());
        } finally {
            jobLogger.detachAppender(logs);
        }

        assertEquals(OrderStatus.EXPIRED, order(failingKey).getStatus());
        assertEquals(OrderStatus.EXPIRED, order(otherKey).getStatus());
        assertStock(itemId, 10, 0);
        assertEquals(failures + 1, counter("saga.refund.failures"));
        assertEquals(refunds + 1, counter("saga.refunds"), "only the refund that went through");
        verify(spiedGateway, times(1)).refund(otherKey);
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->EXPIRED"), actions(failing));
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->EXPIRED", "REFUND_REQUESTED"), actions(other));

        assertEquals(1, logs.list.size());
        ILoggingEvent warning = logs.list.get(0);
        assertEquals(Level.WARN, warning.getLevel());
        assertTrue(warning.getFormattedMessage().contains(failing.toString()), warning.getFormattedMessage());
        assertFalse(warning.getFormattedMessage().contains(failingKey), "the idempotency key is never logged");
    }
}
