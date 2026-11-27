package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every POST here is valid except that the client only accepts XML. It must be refused (406) BEFORE the handler runs:
 * refusing it afterwards, when the answer is written, would save the order and then report a failure.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class UnacceptableAcceptHeaderTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /** Everything a POST can write to: row counts, plus the stock an allocation would take. */
    private Map<String, Object> databaseState() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM orders)           AS orders,
                       (SELECT count(*) FROM allocation)       AS allocations,
                       (SELECT count(*) FROM transaction_log)  AS log_entries,
                       (SELECT count(*) FROM idempotency_key)  AS keys,
                       (SELECT count(*) FROM fulfilment_order) AS fulfilments,
                       (SELECT count(*) FROM warehouse)        AS warehouses,
                       (SELECT count(*) FROM inventory_batch)  AS batches,
                       (SELECT count(*) FROM inventory_item)   AS items,
                       (SELECT sum(quantity_available) FROM inventory_item) AS item_stock,
                       (SELECT sum(qty_available) FROM inventory_batch)     AS batch_stock
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/orders", "/api/checkout", "/api/fulfilments", "/api/inventory", "/api/warehouses",
            "/api/batches"})
    void postAcceptingOnlyXml_returns406_andSavesNothing(String url) throws Exception {
        UUID itemId = inventoryItemRepository.save(new InventoryItem(10)).getItemId();
        UUID warehouseId = UUID.randomUUID();
        String sku = "ACCEPT-" + UUID.randomUUID();
        jdbc.update("INSERT INTO warehouse (id, name, lat, lon) VALUES (?, 'accept', 6.9, 79.8)", warehouseId);
        jdbc.update("INSERT INTO inventory_batch (id, sku, warehouse_id, qty_available, version) VALUES (?, ?, ?, 10, 0)",
                UUID.randomUUID(), sku, warehouseId);
        String body = switch (url) {
            case "/api/orders", "/api/checkout" -> "{\"itemId\":\"" + itemId + "\",\"quantityRequested\":1}";
            case "/api/fulfilments" -> "{\"sku\":\"" + sku + "\",\"quantity\":1,\"deliveryLat\":6.9,\"deliveryLon\":79.8,"
                    + "\"customerTier\":\"STANDARD\"}";
            case "/api/inventory" -> "{\"quantityAvailable\":5}";
            case "/api/warehouses" -> "{\"name\":\"never saved\",\"lat\":6.9,\"lon\":79.8}";
            default -> "{\"sku\":\"" + sku + "\",\"warehouseId\":\"" + warehouseId + "\",\"qtyAvailable\":5}";
        };
        Map<String, Object> before = databaseState();

        MockHttpServletResponse response = mockMvc.perform(post(url)
                        .header("Idempotency-Key", "accept-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content(body))
                .andReturn().getResponse();

        assertEquals(406, response.getStatus(), response.getContentAsString());
        assertTrue(response.getContentAsString().contains("only returns application/json"), response.getContentAsString());
        assertEquals(before, databaseState(), "the refused request must not save or change anything");
    }
}
