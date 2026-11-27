package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.payment.FakePaymentGateway;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * An Idempotency-Key belongs to the flow that used it first (POST /api/orders, /api/checkout or /api/fulfilments). On
 * another flow it is refused with 422: that request must not be answered with the first flow's record, and must not
 * create or change anything.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IdempotencyScopeTest {

    private static final int STOCK = 10;
    private static final int QUANTITY = 4;
    private static final String ANOTHER_FLOW = "already used by a different operation";

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

    /** One flow with its own stock, so "nothing changed" can be checked per request. */
    private record Target(String url, String body, UUID itemId, String sku) {}

    private Target target(String flow) {
        if (flow.equals("fulfilments")) {
            UUID warehouseId = UUID.randomUUID();
            String sku = "SCOPE-" + UUID.randomUUID();
            jdbc.update("INSERT INTO warehouse (id, name, lat, lon) VALUES (?, 'scope', 6.9, 79.8)", warehouseId);
            jdbc.update("INSERT INTO inventory_batch (id, sku, warehouse_id, qty_available, version) VALUES (?, ?, ?, ?, 0)",
                    UUID.randomUUID(), sku, warehouseId, STOCK);
            return new Target("/api/fulfilments", "{\"sku\":\"" + sku + "\",\"quantity\":" + QUANTITY
                    + ",\"deliveryLat\":6.9,\"deliveryLon\":79.8,\"customerTier\":\"STANDARD\"}", null, sku);
        }
        UUID itemId = inventoryItemRepository.save(new InventoryItem(STOCK)).getItemId();
        return itemTarget(flow, itemId);
    }

    private static Target itemTarget(String flow, UUID itemId) {
        return new Target("/api/" + flow, "{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + QUANTITY + "}", itemId, null);
    }

    private MockHttpServletResponse send(Target target, String key) throws Exception {
        return mockMvc.perform(post(target.url()).header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(target.body()))
                .andReturn().getResponse();
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    private static String id(JsonNode record) {
        return record.hasNonNull("orderId") ? record.get("orderId").asText() : record.get("id").asText();
    }

    private int available(Target target) {
        return target.sku() == null
                ? inventoryItemRepository.findById(target.itemId()).orElseThrow().getQuantityAvailable()
                : jdbc.queryForObject("SELECT qty_available FROM inventory_batch WHERE sku = ?", Integer.class, target.sku());
    }

    private int records(String key) {
        return jdbc.queryForObject("SELECT (SELECT count(*) FROM orders WHERE idempotency_key = ?) "
                + "+ (SELECT count(*) FROM fulfilment_order WHERE idempotency_key = ?)", Integer.class, key, key);
    }

    private void assertRefused(MockHttpServletResponse response) throws Exception {
        assertEquals(422, response.getStatus(), response.getContentAsString());
        JsonNode body = json(response);
        assertTrue(body.get("message").asText().contains(ANOTHER_FLOW), body.toString());
        assertFalse(body.has("orderId") || body.has("id"), "no record in the answer: " + body);
    }

    private static String newKey() {
        return "scope-" + UUID.randomUUID();
    }

    @Test
    void directOrderKey_atCheckout_withTheSameItemAndQuantity_returns422_andDoesNothing() throws Exception {
        Target order = target("orders");
        String key = newKey();
        MockHttpServletResponse first = send(order, key);
        assertEquals(201, first.getStatus());

        MockHttpServletResponse response = send(itemTarget("checkout", order.itemId()), key);

        assertRefused(response);
        assertFalse(response.getContentAsString().contains(id(json(first))), "the direct order is not leaked");
        assertEquals(STOCK - QUANTITY, available(order), "only the direct order took stock");
        assertEquals(0, inventoryItemRepository.findById(order.itemId()).orElseThrow().getQuantityReserved());
        assertEquals(0, gateway.chargeCount(key));
        assertEquals(1, records(key));
    }

    @ParameterizedTest(name = "{0} key reused on {1}")
    @CsvSource({
            "orders, checkout",
            "orders, fulfilments",
            "checkout, orders",
            "checkout, fulfilments",
            "fulfilments, orders",
            "fulfilments, checkout"
    })
    void keyReusedOnAnotherFlow_returns422_andChangesNothing(String firstFlow, String secondFlow) throws Exception {
        Target first = target(firstFlow);
        Target second = target(secondFlow);
        String key = newKey();
        MockHttpServletResponse created = send(first, key);
        assertEquals(201, created.getStatus(), created.getContentAsString());
        int chargesBefore = gateway.chargeCount(key);

        MockHttpServletResponse response = send(second, key);

        assertRefused(response);
        assertEquals(STOCK, available(second), "no stock taken by the refused request");
        assertEquals(STOCK - QUANTITY, available(first));
        assertEquals(chargesBefore, gateway.chargeCount(key), "no new charge");
        assertEquals(1, records(key));

        // The key still replays on the flow that owns it.
        MockHttpServletResponse replay = send(first, key);
        assertEquals(201, replay.getStatus());
        assertEquals(id(json(created)), id(json(replay)));
        assertEquals(STOCK - QUANTITY, available(first));
    }

    @Test
    void aRequestThatSavedNothing_doesNotClaimItsKey() throws Exception {
        String key = newKey();
        assertEquals(404, send(itemTarget("orders", UUID.randomUUID()), key).getStatus());
        assertEquals(404, send(itemTarget("checkout", UUID.randomUUID()), key).getStatus());

        assertEquals(201, send(target("fulfilments"), key).getStatus());
    }

    /** Both requests are past the "has this key been used?" check before either commits: only one flow may get the key. */
    @RepeatedTest(3)
    void directOrderAndCheckout_racingWithOneKey_oneWins_theOtherGets422() throws Exception {
        Target order = target("orders");
        Target checkout = itemTarget("checkout", order.itemId());
        String key = newKey();
        AtomicInteger next = new AtomicInteger();

        List<MockHttpServletResponse> responses = LockRace.run(transactionManager, jdbc,
                "SELECT item_id FROM inventory_item WHERE item_id = ? FOR UPDATE", order.itemId(), 2,
                () -> send(next.getAndIncrement() == 0 ? order : checkout, key));

        assertEquals(List.of(201, 422), responses.stream().map(MockHttpServletResponse::getStatus).sorted().toList());
        assertRefused(responses.stream().filter(r -> r.getStatus() == 422).findFirst().orElseThrow());
        assertEquals(1, records(key));
        assertEquals(STOCK - QUANTITY, available(order), "stock taken once");
        assertEquals(0, inventoryItemRepository.findById(order.itemId()).orElseThrow().getQuantityReserved());
    }
}
