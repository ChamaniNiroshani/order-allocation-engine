package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.payment.FakePaymentGateway;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Two requests with the same new Idempotency-Key, both past the replay check before either commits (see LockRace), on
 * each endpoint that takes a key. The unique constraint settles it: one request wins, the other's transaction rolls back
 * and it is answered with the stored order. Stock is exactly enough for ONE order, so a loser that saw the stock gone
 * must still get the winner's order, not a rejection. (POST /api/orders in all three locking modes:
 * AbstractAllocationConcurrencyTest.)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IdempotencyRaceTest {

    private static final int QUANTITY = 4;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private FakePaymentGateway gateway;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private List<MockHttpServletResponse> race(String lockSql, Object lockArg, String url, String key, String body) throws Exception {
        return LockRace.run(transactionManager, jdbc, lockSql, lockArg, 2, () -> mockMvc
                .perform(post(url).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse());
    }

    private String field(MockHttpServletResponse response, String name) throws Exception {
        return objectMapper.readTree(response.getContentAsString()).get(name).asText();
    }

    private int count(String sql, Object arg) {
        return jdbc.queryForObject(sql, Integer.class, arg);
    }

    private static String newKey() {
        return "race-" + UUID.randomUUID();
    }

    @RepeatedTest(3)
    void orders_bothGetTheSameOrder_andStockIsTakenOnce() throws Exception {
        UUID itemId = inventoryItemRepository.save(new InventoryItem(QUANTITY)).getItemId();
        String key = newKey();

        try (LogCapture logs = new LogCapture()) {
            List<MockHttpServletResponse> responses = race(
                    "SELECT item_id FROM inventory_item WHERE item_id = ? FOR UPDATE", itemId, "/api/orders", key,
                    "{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + QUANTITY + "}");

            assertEquals(List.of(201, 201), responses.stream().map(MockHttpServletResponse::getStatus).toList());
            assertEquals(field(responses.get(0), "orderId"), field(responses.get(1), "orderId"));
            assertEquals(1, logs.events("idempotency.conflict").size(), "the loser went through the constraint path");
        }
        assertEquals(1, count("SELECT count(*) FROM orders WHERE idempotency_key = ?", key));
        assertEquals(1, count("SELECT count(*) FROM allocation WHERE item_id = ?", itemId));
        assertEquals(0, inventoryItemRepository.findById(itemId).orElseThrow().getQuantityAvailable());
    }

    @RepeatedTest(3)
    void checkout_oneOrder_oneReservation_oneCharge() throws Exception {
        UUID itemId = inventoryItemRepository.save(new InventoryItem(QUANTITY)).getItemId();
        String key = newKey();

        try (LogCapture logs = new LogCapture()) {
            List<MockHttpServletResponse> responses = race(
                    "SELECT item_id FROM inventory_item WHERE item_id = ? FOR UPDATE", itemId, "/api/checkout", key,
                    "{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + QUANTITY + "}");

            // The loser is answered while the winner may still be paying: 202 (in progress) or already 201.
            List<Integer> statuses = responses.stream().map(MockHttpServletResponse::getStatus).toList();
            assertTrue(statuses.contains(201) && statuses.stream().allMatch(s -> s == 201 || s == 202), statuses.toString());
            assertEquals(field(responses.get(0), "orderId"), field(responses.get(1), "orderId"));
            assertEquals(1, logs.events("idempotency.conflict").size(), "the loser went through the constraint path");
        }
        assertEquals(1, count("SELECT count(*) FROM orders WHERE idempotency_key = ?", key));
        assertEquals(1, gateway.chargeCount(key), "charged once");
        InventoryItem item = inventoryItemRepository.findById(itemId).orElseThrow();
        assertEquals(0, item.getQuantityAvailable());
        assertEquals(0, item.getQuantityReserved(), "one reservation, confirmed");
    }

    @RepeatedTest(3)
    void fulfilments_bothGetTheSameFulfilment_andStockIsTakenOnce() throws Exception {
        UUID warehouseId = UUID.randomUUID();
        String sku = "RACE-" + UUID.randomUUID();
        jdbc.update("INSERT INTO warehouse (id, name, lat, lon) VALUES (?, 'race', 6.9, 79.8)", warehouseId);
        jdbc.update("INSERT INTO inventory_batch (id, sku, warehouse_id, qty_available, version) VALUES (?, ?, ?, ?, 0)",
                UUID.randomUUID(), sku, warehouseId, QUANTITY);
        String key = newKey();

        List<MockHttpServletResponse> responses = race(
                "SELECT id FROM inventory_batch WHERE sku = ? ORDER BY id FOR UPDATE", sku, "/api/fulfilments", key,
                "{\"sku\":\"" + sku + "\",\"quantity\":" + QUANTITY
                        + ",\"deliveryLat\":6.9,\"deliveryLon\":79.8,\"customerTier\":\"STANDARD\"}");

        assertEquals(List.of(201, 201), responses.stream().map(MockHttpServletResponse::getStatus).toList());
        assertEquals(field(responses.get(0), "id"), field(responses.get(1), "id"));
        assertEquals(1, count("SELECT count(*) FROM fulfilment_order WHERE idempotency_key = ?", key));
        assertEquals(QUANTITY, count("SELECT COALESCE(SUM(l.quantity), 0) FROM fulfilment_line l "
                + "JOIN fulfilment_order o ON o.id = l.fulfilment_order_id WHERE o.idempotency_key = ?", key));
        assertEquals(0, count("SELECT qty_available FROM inventory_batch WHERE sku = ?", sku));
    }
}
