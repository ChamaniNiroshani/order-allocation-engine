package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.metrics.ActiveReservationsRefresher;
import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.metrics.AllocationMetrics;
import com.chamani.order_allocation_engine.saga.CheckoutSteps;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The business metrics move on the real request paths (default gateway: healthy, no failures).
 * Not an AbstractSagaScenarioTest: that one checks the saga's stock invariant, and direct-flow orders end ALLOCATED, not CONFIRMED.
 * Same annotations, so it shares the default Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AllocationMetricsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private ActiveReservationsRefresher refresher;

    @Autowired
    private CheckoutSteps steps;

    private UUID newItem(int stock) {
        return inventoryItemRepository.save(new InventoryItem(stock)).getItemId();
    }

    private static String newKey() {
        return "metrics-" + UUID.randomUUID();
    }

    private ResultActions checkout(UUID itemId, int quantity, String key) throws Exception {
        return mockMvc.perform(post("/api/checkout")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + quantity + "}"));
    }

    private void assertStock(UUID itemId, int available, int reserved) {
        InventoryItem item = inventoryItemRepository.findById(itemId).orElseThrow();
        assertEquals(available, item.getQuantityAvailable());
        assertEquals(reserved, item.getQuantityReserved());
    }

    private double requests(String flow, String outcome) {
        return registry.get(AllocationMetrics.REQUESTS).tag("flow", flow).tag("outcome", outcome).counter().count();
    }

    private long timed(String flow) {
        return registry.get(AllocationMetrics.LATENCY).tag("flow", flow).timer().count();
    }

    private double compensations() {
        return registry.find(AllocationMetrics.COMPENSATIONS).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private ResultActions direct(UUID itemId, int quantity, String key) throws Exception {
        return mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + quantity + "}"));
    }

    @Test
    void directFlow_countsAllocatedAndRejected_andTimesBoth() throws Exception {
        UUID itemId = newItem(5);
        double allocated = requests("direct", "allocated");
        double rejected = requests("direct", "rejected");
        long timedBefore = timed("direct");

        direct(itemId, 5, newKey()).andExpect(status().isCreated());
        direct(itemId, 1, newKey()).andExpect(status().isConflict());           // nothing left

        assertEquals(allocated + 1, requests("direct", "allocated"));
        assertEquals(rejected + 1, requests("direct", "rejected"));
        assertEquals(timedBefore + 2, timed("direct"));
    }

    @Test
    void directFlow_aRetryWithTheSameKey_isStillARequest() throws Exception {
        UUID itemId = newItem(5);
        String key = newKey();
        double allocated = requests("direct", "allocated");

        direct(itemId, 2, key).andExpect(status().isCreated());
        direct(itemId, 2, key).andExpect(status().isCreated());

        assertEquals(allocated + 2, requests("direct", "allocated"));
        assertStock(itemId, 3, 0);                                               // ...though it allocated once
    }

    @Test
    void directFlow_aReusedKeyWithAnotherPayload_isRejected_notAnError() throws Exception {
        UUID itemId = newItem(5);
        String key = newKey();
        direct(itemId, 2, key).andExpect(status().isCreated());
        double rejected = requests("direct", "rejected");
        double errors = requests("direct", "error");

        direct(itemId, 3, key).andExpect(status().isUnprocessableEntity());

        assertEquals(rejected + 1, requests("direct", "rejected"));
        assertEquals(errors, requests("direct", "error"));
    }

    @Test
    void checkoutFlow_countsConfirmedAndOutOfStock_onTheCheckoutSeries() throws Exception {
        UUID itemId = newItem(2);
        double allocated = requests("checkout", "allocated");
        double rejected = requests("checkout", "rejected");
        double directAllocated = requests("direct", "allocated");
        long timedBefore = timed("checkout");

        checkout(itemId, 2, newKey()).andExpect(status().isCreated());
        checkout(itemId, 1, newKey()).andExpect(status().isConflict());

        assertEquals(allocated + 1, requests("checkout", "allocated"));
        assertEquals(rejected + 1, requests("checkout", "rejected"));
        assertEquals(directAllocated, requests("direct", "allocated"), "the two flows are counted separately");
        assertEquals(timedBefore + 2, timed("checkout"));
    }

    @Test
    void checkoutFlow_aSuccessfulSaga_compensatesAndRefundsNothing() throws Exception {
        UUID itemId = newItem(2);
        double refunds = registry.get(AllocationMetrics.REFUNDS).counter().count();
        double compensations = compensations();

        checkout(itemId, 1, newKey()).andExpect(status().isCreated());

        assertEquals(refunds, registry.get(AllocationMetrics.REFUNDS).counter().count());
        assertEquals(compensations, compensations());
    }

    @Test
    void activeReservationsGauge_followsTheRefresher_notTheDatabase() {
        UUID itemId = newItem(10);
        long before = refresher.refresh();

        steps.reserve(itemId, 2, newKey(), "h1");
        steps.reserve(itemId, 3, newKey(), "h2");
        assertEquals(before, registry.get(AllocationMetrics.ACTIVE_RESERVATIONS).gauge().value(),
                "the gauge shows the cached value until the next refresh, it does not query on read");

        assertEquals(before + 2, refresher.refresh());
        assertEquals(before + 2, registry.get(AllocationMetrics.ACTIVE_RESERVATIONS).gauge().value());

        // put the two reservations back so the stock invariant (and the gauge) are clean for the next test
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE item_id = ? AND status = 'RESERVED'", itemId);
        jdbc.update("UPDATE inventory_item SET quantity_available = 10, quantity_reserved = 0 WHERE item_id = ?", itemId);
        assertEquals(before, refresher.refresh());
    }
}
