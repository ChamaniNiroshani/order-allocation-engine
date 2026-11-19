package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.payment.FakePaymentGateway;
import com.chamani.order_allocation_engine.payment.PaymentResult;
import com.chamani.order_allocation_engine.payment.PaymentTimeoutException;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.saga.CheckoutSteps;
import com.chamani.order_allocation_engine.service.StockReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/checkout end to end: every saga branch, replay, and the "no transaction during the charge" rule. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"payment.fake.latency-ms=0", "payment.fake.timeout-ms=0"})
class CheckoutSagaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CheckoutSteps steps;

    @Autowired
    private StockReservationService stock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The real fake gateway, wrapped so a test can stub or observe a call. Reset after every test. */
    @MockitoSpyBean
    private FakePaymentGateway gateway;

    private UUID newItem(int available) {
        return inventoryItemRepository.save(new InventoryItem(available)).getItemId();
    }

    private static String newKey() {
        return "saga-" + UUID.randomUUID();
    }

    private ResultActions checkout(UUID itemId, int quantity, String key) throws Exception {
        return mockMvc.perform(post("/api/checkout")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + quantity + "}"));
    }

    private void assertStock(UUID itemId, int available, int reserved) {
        InventoryItem item = inventoryItemRepository.findById(itemId).orElseThrow();
        assertEquals(available, item.getQuantityAvailable(), "available");
        assertEquals(reserved, item.getQuantityReserved(), "reserved");
    }

    private Order order(String key) {
        return orderRepository.findByIdempotencyKey(key).orElseThrow();
    }

    @Test
    void happyPath_confirmsTheOrder_andConsumesTheStock() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();

        checkout(itemId, 3, key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.quantityAllocated").value(3));

        assertStock(itemId, 7, 0);
        Order stored = order(key);
        assertEquals(OrderStatus.CONFIRMED, stored.getStatus());
        assertTrue(stored.getPaymentId().startsWith("pay-"));
        assertEquals(1, gateway.chargeCount(key));
    }

    @Test
    void charge_isTheUnitPriceTimesTheQuantity() throws Exception {
        UUID itemId = newItem(10);          // unit price defaults to 10.00
        String key = newKey();

        checkout(itemId, 3, key).andExpect(status().isCreated());

        verify(gateway).charge(any(), argThat(amount -> amount.compareTo(new BigDecimal("30.00")) == 0), eq(key));
    }

    @Test
    void insufficientStock_returns409_withoutCallingThePaymentGateway() throws Exception {
        UUID itemId = newItem(2);
        String key = newKey();

        checkout(itemId, 5, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reason").value("Insufficient inventory"));

        assertStock(itemId, 2, 0);
        verify(gateway, never()).charge(any(), any(), any());
    }

    @Test
    void paymentDeclined_returns402_andReleasesTheStock() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        doReturn(PaymentResult.declined("Card declined")).when(gateway).charge(any(), any(), eq(key));

        checkout(itemId, 4, key)
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.reason").value("Payment declined: Card declined"));

        assertStock(itemId, 10, 0);
        assertEquals(OrderStatus.CANCELLED, order(key).getStatus());
        verify(gateway, never()).refund(any());
    }

    @Test
    void paymentTimeout_returns502_refunds_andReleasesTheStock() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        doThrow(new PaymentTimeoutException(key)).when(gateway).charge(any(), any(), eq(key));

        checkout(itemId, 4, key)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.reason").value("Payment timed out"));

        assertStock(itemId, 10, 0);
        verify(gateway).refund(key);
    }

    @Test
    void unknownItem_returns404_andStoresNoOrder() throws Exception {
        String key = newKey();

        checkout(UUID.randomUUID(), 1, key).andExpect(status().isNotFound());

        assertTrue(orderRepository.findByIdempotencyKey(key).isEmpty());
    }

    @Test
    void sameKeyTwice_returnsTheStoredOrder_withoutReservingOrChargingAgain() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();

        String first = checkout(itemId, 3, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = checkout(itemId, 3, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        assertStock(itemId, 7, 0);
        verify(gateway, times(1)).charge(any(), any(), eq(key));
    }

    @Test
    void replayOfADeclinedCheckout_returnsTheSame402() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        doReturn(PaymentResult.declined("Card declined")).when(gateway).charge(any(), any(), eq(key));

        checkout(itemId, 4, key).andExpect(status().isPaymentRequired());
        checkout(itemId, 4, key).andExpect(status().isPaymentRequired());

        assertStock(itemId, 10, 0);
        verify(gateway, times(1)).charge(any(), any(), eq(key));
    }

    @Test
    void replayOfATimedOutCheckout_returnsTheSame502() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        doThrow(new PaymentTimeoutException(key)).when(gateway).charge(any(), any(), eq(key));

        checkout(itemId, 4, key).andExpect(status().isBadGateway());
        checkout(itemId, 4, key).andExpect(status().isBadGateway());

        verify(gateway, times(1)).charge(any(), any(), eq(key));
    }

    @Test
    void sameKeyWithADifferentPayload_returns422_andChangesNothing() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        checkout(itemId, 3, key).andExpect(status().isCreated());

        checkout(itemId, 5, key).andExpect(status().isUnprocessableEntity());

        assertStock(itemId, 7, 0);
        verify(gateway, times(1)).charge(any(), any(), eq(key));
    }

    @Test
    void noTransactionIsActive_whileThePaymentGatewayIsCalled() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        List<Boolean> transactionActive = new ArrayList<>();
        List<Boolean> synchronizationActive = new ArrayList<>();
        doAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            synchronizationActive.add(TransactionSynchronizationManager.isSynchronizationActive());
            return invocation.callRealMethod();
        }).when(gateway).charge(any(), any(), eq(key));

        checkout(itemId, 2, key).andExpect(status().isCreated());

        assertEquals(List.of(false), transactionActive, "the gateway must be called outside any transaction");
        assertEquals(List.of(false), synchronizationActive);
    }

    @Test
    void orderExpiredWhilePaymentIsInFlight_isRefunded_andTheStockIsNotConsumed() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        // While the charge is "in flight", do what the expiry job does: expire the order and release its stock.
        doAnswer(invocation -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                Order o = orderRepository.findByIdForUpdate(order(key).getOrderId()).orElseThrow();
                o.expire();
                stock.release(o.getItemId(), o.getQuantityRequested());
            });
            return invocation.callRealMethod();
        }).when(gateway).charge(any(), any(), eq(key));

        checkout(itemId, 4, key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        Order stored = order(key);
        assertEquals(OrderStatus.EXPIRED, stored.getStatus());
        assertTrue(stored.getFailureReason().contains("payment refunded"), stored.getFailureReason());
        assertEquals(1, gateway.chargeCount(key));
        assertEquals(1, gateway.refundCount(key), "the late payment must be given back");
        assertStock(itemId, 10, 0);
    }

    @Test
    void compensation_isIdempotent_stockIsReleasedOnce() {
        UUID itemId = newItem(10);
        String key = newKey();
        CheckoutSteps.Reservation reservation = steps.reserve(itemId, 4, key, "hash");
        assertEquals(OrderStatus.RESERVED, reservation.order().getStatus());
        assertStock(itemId, 6, 4);

        Order first = steps.compensate(reservation.order().getOrderId(), "first");
        Order second = steps.compensate(reservation.order().getOrderId(), "second");

        assertEquals(OrderStatus.CANCELLED, first.getStatus());
        assertEquals(OrderStatus.CANCELLED, second.getStatus());
        assertEquals("first", order(key).getFailureReason(), "the second compensation must change nothing");
        assertStock(itemId, 10, 0);
    }

    @Test
    void confirm_isIdempotent_stockIsConsumedOnce() {
        UUID itemId = newItem(10);
        CheckoutSteps.Reservation reservation = steps.reserve(itemId, 4, newKey(), "hash");
        UUID orderId = reservation.order().getOrderId();
        steps.recordPayment(orderId, "pay-x");

        steps.confirm(orderId);
        steps.confirm(orderId);

        assertStock(itemId, 6, 0);
    }

    @Test
    void failedReservation_leavesNoReservedStock() {
        UUID itemId = newItem(3);

        CheckoutSteps.Reservation reservation = steps.reserve(itemId, 5, newKey(), "hash");

        assertEquals(OrderStatus.FAILED, reservation.order().getStatus());
        assertFalse(reservation.amount() != null);
        assertStock(itemId, 3, 0);
    }
}
