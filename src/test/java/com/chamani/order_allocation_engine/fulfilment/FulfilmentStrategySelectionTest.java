package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Same stock, same order, different strategy, different lines. The order (60 units, delivered to Colombo):
 * Colombo 15 (expires in 3 days) + 25 (20 days), Kandy 100 (10 days), Galle 30 (45 days).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FulfilmentStrategySelectionTest {

    @Autowired MockMvc mockMvc;
    @Autowired WarehouseRepository warehouses;
    @Autowired InventoryBatchRepository batches;
    @Autowired MeterRegistry meters;

    private final ObjectMapper json = new ObjectMapper();

    /** A fresh SKU with the stock described above. Returns [sku, colombo15, colombo25, kandy100, galle30]. */
    private record Stock(String sku, Warehouse colombo, Warehouse kandy, Warehouse galle,
                         InventoryBatch colombo15, InventoryBatch colombo25, InventoryBatch kandy100, InventoryBatch galle30) {
    }

    private Stock stock() {
        Warehouse colombo = warehouses.save(new Warehouse("Colombo", new BigDecimal("6.9271"), new BigDecimal("79.8612")));
        Warehouse kandy = warehouses.save(new Warehouse("Kandy", new BigDecimal("7.2906"), new BigDecimal("80.6337")));
        Warehouse galle = warehouses.save(new Warehouse("Galle", new BigDecimal("6.0535"), new BigDecimal("80.2210")));
        String sku = "SEL-" + UUID.randomUUID();
        LocalDate today = LocalDate.now();
        return new Stock(sku, colombo, kandy, galle,
                batches.save(new InventoryBatch(sku, colombo.getId(), 15, today.plusDays(3))),
                batches.save(new InventoryBatch(sku, colombo.getId(), 25, today.plusDays(20))),
                batches.save(new InventoryBatch(sku, kandy.getId(), 100, today.plusDays(10))),
                batches.save(new InventoryBatch(sku, galle.getId(), 30, today.plusDays(45))));
    }

    private ResultActions order(String sku, String tier, String strategyJson) throws Exception {
        return mockMvc.perform(post("/api/fulfilments").header("Idempotency-Key", "sel-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"" + sku + "\",\"quantity\":60,\"deliveryLat\":6.9271,\"deliveryLon\":79.8612,"
                        + "\"customerTier\":\"" + tier + "\"" + strategyJson + "}"));
    }

    /** "batchId=quantity" pairs of the response, in line order. */
    private List<String> lines(ResultActions result) throws Exception {
        JsonNode body = json.readTree(result.andReturn().getResponse().getContentAsString());
        List<String> lines = new ArrayList<>();
        body.get("lines").forEach(l -> lines.add(l.get("batchId").asText() + "=" + l.get("quantity").asInt()));
        return lines;
    }

    @Test
    void sameStockSameOrder_differentStrategy_differentLines() throws Exception {
        Stock nearest = stock();
        Stock fefo = stock();
        Stock split = stock();

        List<String> nearestLines = lines(order(nearest.sku(), "STANDARD", ",\"strategy\":\"NEAREST\"")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.strategy").value("NEAREST")));
        List<String> fefoLines = lines(order(fefo.sku(), "STANDARD", ",\"strategy\":\"FEFO\"")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.strategy").value("FEFO")));
        List<String> splitLines = lines(order(split.sku(), "STANDARD", ",\"strategy\":\"SPLIT\"")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.strategy").value("SPLIT")));

        // NEAREST: Colombo only holds 40, so the nearest warehouse that can cover all 60 is Kandy
        assertThat(nearestLines).containsExactly(nearest.kandy100().getId() + "=60");
        // FEFO: earliest expiry first, wherever it is: Colombo's 3-day batch, then Kandy's 10-day batch
        assertThat(fefoLines).containsExactly(fefo.colombo15().getId() + "=15", fefo.kandy100().getId() + "=45");
        // SPLIT: all of Colombo (earliest expiry first), the rest from the next nearest, Kandy
        assertThat(splitLines).containsExactly(split.colombo15().getId() + "=15", split.colombo25().getId() + "=25",
                split.kandy100().getId() + "=20");

        assertThat(nearestLines).isNotEqualTo(fefoLines).isNotEqualTo(splitLines);
        assertThat(fefoLines).isNotEqualTo(splitLines);
        // and the stock really moved accordingly
        assertThat(batches.findById(nearest.kandy100().getId()).orElseThrow().getQtyAvailable()).isEqualTo(40);
        assertThat(batches.findById(fefo.colombo15().getId()).orElseThrow().getQtyAvailable()).isZero();
        assertThat(batches.findById(split.kandy100().getId()).orElseThrow().getQtyAvailable()).isEqualTo(80);
    }

    @Test
    void withoutAStrategy_theTierDefaultApplies() throws Exception {
        Stock priority = stock();
        Stock standard = stock();

        order(priority.sku(), "PRIORITY", "").andExpect(status().isCreated()).andExpect(jsonPath("$.strategy").value("NEAREST"));
        order(standard.sku(), "STANDARD", "").andExpect(status().isCreated()).andExpect(jsonPath("$.strategy").value("FEFO"));
    }

    @Test
    void anExplicitStrategy_winsOverTheTierDefault_andIsStoredAndReadBack() throws Exception {
        Stock stock = stock();

        String body = order(stock.sku(), "PRIORITY", ",\"strategy\":\"fefo\"")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.strategy").value("FEFO"))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/fulfilments/" + json.readTree(body).get("id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.strategy").value("FEFO"));
    }

    @Test
    void unknownStrategy_is400_andNothingIsStored() throws Exception {
        Stock stock = stock();

        order(stock.sku(), "STANDARD", ",\"strategy\":\"CHEAPEST\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unknown strategy 'CHEAPEST'")));

        assertThat(batches.findById(stock.kandy100().getId()).orElseThrow().getQtyAvailable()).isEqualTo(100);
    }

    @Test
    void sameKeyWithAnotherStrategy_is422() throws Exception {
        Stock stock = stock();
        String key = "sel-" + UUID.randomUUID();
        String base = "{\"sku\":\"" + stock.sku() + "\",\"quantity\":10,\"deliveryLat\":6.9271,\"deliveryLon\":79.8612,\"customerTier\":\"STANDARD\"";

        mockMvc.perform(post("/api/fulfilments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(base + ",\"strategy\":\"FEFO\"}")).andExpect(status().isCreated());
        mockMvc.perform(post("/api/fulfilments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(base + ",\"strategy\":\"SPLIT\"}")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void metrics_countRequestsAndTimeThem_withFlowAndStrategyTags() throws Exception {
        double before = count("FEFO", "allocated");
        double rejectedBefore = count("FEFO", "rejected");
        long timedBefore = timed("FEFO");
        Stock stock = stock();

        order(stock.sku(), "STANDARD", ",\"strategy\":\"FEFO\"").andExpect(status().isCreated());
        order("NO-SUCH-" + UUID.randomUUID(), "STANDARD", ",\"strategy\":\"FEFO\"").andExpect(status().isConflict());

        assertThat(count("FEFO", "allocated")).isEqualTo(before + 1);
        assertThat(count("FEFO", "rejected")).isEqualTo(rejectedBefore + 1);
        assertThat(timed("FEFO")).isEqualTo(timedBefore + 2);
    }

    private double count(String strategy, String outcome) {
        return meters.get("allocation.requests").tag("flow", "fulfilment").tag("strategy", strategy).tag("outcome", outcome).counter().count();
    }

    private long timed(String strategy) {
        return meters.get("allocation.latency").tag("flow", "fulfilment").tag("strategy", strategy).timer().count();
    }
}
