package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.TransactionLog;
import com.chamani.order_allocation_engine.payment.FakePaymentGateway;
import com.chamani.order_allocation_engine.payment.PaymentResult;
import com.chamani.order_allocation_engine.payment.PaymentTimeoutException;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.repository.TransactionLogRepository;
import com.chamani.order_allocation_engine.saga.CheckoutSteps;
import com.chamani.order_allocation_engine.service.OrderAuditService;
import com.chamani.order_allocation_engine.service.StockReservationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The audit trail: one row per status change, in the same transaction, in both flows, readable over HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"payment.fake.latency-ms=0", "payment.fake.timeout-ms=0"})
class AuditLogTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CheckoutSteps steps;

    @Autowired
    private OrderAuditService audit;

    @Autowired
    private StockReservationService stock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoSpyBean
    private FakePaymentGateway gateway;

    private UUID newItem(int available) {
        return inventoryItemRepository.save(new InventoryItem(available)).getItemId();
    }

    private static String newKey() {
        return "audit-" + UUID.randomUUID();
    }

    private String body(UUID itemId, int quantity) {
        return "{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + quantity + "}";
    }

    private UUID postOrder(String url, UUID itemId, int quantity, String key) throws Exception {
        String response = mockMvc.perform(post(url)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(itemId, quantity)))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("orderId").asText());
    }

    private JsonNode history(UUID orderId) throws Exception {
        String json = mockMvc.perform(get("/api/orders/" + orderId + "/history"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private List<String> actions(JsonNode history) {
        List<String> actions = new ArrayList<>();
        history.forEach(entry -> actions.add(entry.get("action").asText()));
        return actions;
    }

    // ---- direct flow ------------------------------------------------------------------------------------

    @Test
    void directFlow_allocation_writesOneRowWithBeforeAndAfterState() throws Exception {
        UUID orderId = postOrder("/api/orders", newItem(10), 4, newKey());

        JsonNode history = history(orderId);

        assertEquals(List.of("PENDING->ALLOCATED"), actions(history));
        JsonNode entry = history.get(0);
        assertEquals("PENDING", entry.get("beforeState").get("status").asText());
        assertEquals("ALLOCATED", entry.get("afterState").get("status").asText());
        assertEquals(4, entry.get("afterState").get("quantity").asInt());
        assertFalse(entry.get("timestamp").isNull());
    }

    @Test
    void directFlow_insufficientStock_writesAFailedRowWithTheReason() throws Exception {
        UUID orderId = postOrder("/api/orders", newItem(2), 5, newKey());

        JsonNode history = history(orderId);

        assertEquals(List.of("PENDING->FAILED"), actions(history));
        assertTrue(history.get(0).get("afterState").get("reason").asText().contains("only 2 available"));
    }

    @Test
    void directFlow_replayWritesNoExtraRow() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        UUID orderId = postOrder("/api/orders", itemId, 4, key);
        postOrder("/api/orders", itemId, 4, key);

        assertEquals(1, history(orderId).size());
    }

    // ---- saga -------------------------------------------------------------------------------------------

    @Test
    void saga_happyPath_logsEveryTransition_inOrder() throws Exception {
        UUID orderId = postOrder("/api/checkout", newItem(10), 3, newKey());

        JsonNode history = history(orderId);

        assertEquals(List.of("PENDING->RESERVED", "RESERVED->PAID", "PAID->CONFIRMED"), actions(history));
        assertTrue(history.get(1).get("afterState").get("paymentId").asText().startsWith("pay-"));
        Instant previous = Instant.MIN;
        for (JsonNode entry : history) {
            Instant at = Instant.parse(entry.get("timestamp").asText());
            assertFalse(at.isBefore(previous), "history must be ordered by timestamp");
            previous = at;
        }
    }

    @Test
    void saga_insufficientStock_logsPendingToFailed() throws Exception {
        UUID orderId = postOrder("/api/checkout", newItem(1), 5, newKey());

        assertEquals(List.of("PENDING->FAILED"), actions(history(orderId)));
    }

    @Test
    void saga_declined_logsReservedThenCancelled() throws Exception {
        String key = newKey();
        doReturn(PaymentResult.declined("Card declined")).when(gateway).charge(any(), any(), eq(key));

        UUID orderId = postOrder("/api/checkout", newItem(10), 3, key);

        JsonNode history = history(orderId);
        assertEquals(List.of("PENDING->RESERVED", "RESERVED->CANCELLED"), actions(history));
        assertEquals("Payment declined: Card declined", history.get(1).get("afterState").get("reason").asText());
    }

    @Test
    void saga_timeout_logsReservedThenCancelled() throws Exception {
        String key = newKey();
        doThrow(new PaymentTimeoutException(key)).when(gateway).charge(any(), any(), eq(key));

        UUID orderId = postOrder("/api/checkout", newItem(10), 3, key);

        assertEquals(List.of("PENDING->RESERVED", "RESERVED->CANCELLED"), actions(history(orderId)));
    }

    @Test
    void saga_expiredWhilePaying_logsTheExpiry_andTheRefund() throws Exception {
        UUID itemId = newItem(10);
        String key = newKey();
        // Do what the expiry job does while the charge is in flight: expire + release + audit, in one transaction.
        doAnswer(invocation -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                Order o = orderRepository.findByIdForUpdate(orderRepository.findByIdempotencyKey(key).orElseThrow().getOrderId()).orElseThrow();
                OrderAuditService.Snapshot before = audit.snapshot(o);
                o.expire();
                stock.release(o.getItemId(), o.getQuantityRequested());
                audit.record(o, before);
            });
            return invocation.callRealMethod();
        }).when(gateway).charge(any(), any(), eq(key));

        UUID orderId = postOrder("/api/checkout", itemId, 4, key);

        assertEquals(List.of("PENDING->RESERVED", "RESERVED->EXPIRED", "PAYMENT_REFUNDED"), actions(history(orderId)));
    }

    // ---- same-transaction guarantee ---------------------------------------------------------------------

    @Test
    void auditRows_rollBackWithTheChange() {
        UUID itemId = newItem(10);
        String key = newKey();
        AtomicReference<UUID> orderId = new AtomicReference<>();

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            CheckoutSteps.Reservation r = steps.reserve(itemId, 4, key, "hash");     // joins this transaction
            orderId.set(r.order().getOrderId());
            throw new IllegalStateException("something failed after the step");
        }));

        assertTrue(orderRepository.findById(orderId.get()).isEmpty(), "the order rolled back");
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM transaction_log WHERE entity_id = ?", Integer.class, orderId.get());
        assertEquals(0, rows, "the audit row must roll back with it");
        assertEquals(10, inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable());
    }

    @Test
    void recordingWithoutATransaction_isRefused() {
        Order order = new Order(UUID.randomUUID(), 1, newKey());
        OrderAuditService.Snapshot before = audit.snapshot(order);

        assertThrows(IllegalTransactionStateException.class, () -> audit.record(order, before));
    }

    // ---- append-only ------------------------------------------------------------------------------------

    @Test
    void theLog_hasNoUpdateOrDeletePath() {
        assertFalse(JpaRepository.class.isAssignableFrom(TransactionLogRepository.class),
                "JpaRepository would expose delete/deleteAll");
        for (Class<?> type : List.of(TransactionLogRepository.class, OrderAuditService.class, TransactionLog.class)) {
            List<String> names = Arrays.stream(type.getMethods()).map(Method::getName).toList();
            for (String name : names) {
                assertFalse(name.startsWith("delete") || name.startsWith("remove") || name.startsWith("update") || name.startsWith("set"),
                        type.getSimpleName() + " must be append-only, found " + name);
            }
        }
    }

    // ---- GET /api/orders/{id}/history -------------------------------------------------------------------

    @Test
    void history_ofAnUnknownOrder_returns404() throws Exception {
        mockMvc.perform(get("/api/orders/" + UUID.randomUUID() + "/history")).andExpect(status().isNotFound());
    }

    @Test
    void history_ofAnOrderWithNoRows_isAnEmptyList() throws Exception {
        Order legacy = orderRepository.save(new Order(newItem(1), 1, newKey()));    // created outside both flows

        assertEquals(0, history(legacy.getOrderId()).size());
    }

    @Test
    void history_withAMalformedId_returns400() throws Exception {
        mockMvc.perform(get("/api/orders/not-a-uuid/history")).andExpect(status().isBadRequest());
    }
}
