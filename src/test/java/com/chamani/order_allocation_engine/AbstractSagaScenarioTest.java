package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.payment.FakePaymentGateway;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.repository.TransactionLogRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared helpers for the saga failure scenarios. Every test ends with the same check: for each item the
 * test created, available + reserved + confirmed quantity == the stock it started with, and nothing is negative.
 * Subclasses differ only in how the fake payment gateway is configured (payment.fake.*).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class AbstractSagaScenarioTest {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected InventoryItemRepository inventoryItemRepository;

    @Autowired
    protected OrderRepository orderRepository;

    @Autowired
    protected TransactionLogRepository transactionLogRepository;

    @Autowired
    protected FakePaymentGateway gateway;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MeterRegistry meterRegistry;

    private final Map<UUID, Integer> initialStock = new ConcurrentHashMap<>();

    protected UUID newItem(int stock) {
        UUID id = inventoryItemRepository.save(new InventoryItem(stock)).getItemId();
        initialStock.put(id, stock);
        return id;
    }

    protected static String newKey() {
        return "scenario-" + UUID.randomUUID();
    }

    protected ResultActions checkout(UUID itemId, int quantity, String key) throws Exception {
        return mockMvc.perform(post("/api/checkout")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + quantity + "}"));
    }

    protected Order order(String key) {
        return orderRepository.findByIdempotencyKey(key).orElseThrow();
    }

    protected List<String> actions(UUID orderId) {
        return transactionLogRepository.findByEntityIdOrderByTimestampAsc(orderId).stream().map(l -> l.getAction()).toList();
    }

    /** Current value of one counter, e.g. counter("saga.compensations", "reason", "declined"). Tests compare before and after. */
    protected double counter(String name, String... tagKeyValues) {
        return meterRegistry.get(name).tags(tagKeyValues).counter().count();
    }

    protected int ordersWithKey(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = ?", Integer.class, key);
    }

    protected void assertStock(UUID itemId, int available, int reserved) {
        InventoryItem item = inventoryItemRepository.findById(itemId).orElseThrow();
        assertEquals(available, item.getQuantityAvailable(), "available");
        assertEquals(reserved, item.getQuantityReserved(), "reserved");
    }

    /** available + reserved + confirmed == initial stock, for every item this test created. */
    @AfterEach
    void stockInvariantHolds() {
        for (Map.Entry<UUID, Integer> item : initialStock.entrySet()) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT quantity_available AS available, quantity_reserved AS reserved FROM inventory_item WHERE item_id = ?",
                    item.getKey());
            int available = ((Number) row.get("available")).intValue();
            int reserved = ((Number) row.get("reserved")).intValue();
            int confirmed = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(quantity_requested), 0) FROM orders WHERE item_id = ? AND status = 'CONFIRMED'",
                    Integer.class, item.getKey());

            assertTrue(available >= 0 && reserved >= 0, "stock must never go negative: " + available + " / " + reserved);
            assertEquals(item.getValue(), available + reserved + confirmed,
                    "available + reserved + confirmed must equal the initial stock of item " + item.getKey());
        }
    }
}
