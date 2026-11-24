package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.TestcontainersConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/fulfilments end to end: stock really moves, rejections are stored, retries are idempotent. Each test uses its own SKU. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FulfilmentApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired WarehouseRepository warehouses;
    @Autowired InventoryBatchRepository batches;

    private final ObjectMapper json = new ObjectMapper();

    private Warehouse warehouse(String name, String lat, String lon) {
        return warehouses.save(new Warehouse(name, new BigDecimal(lat), new BigDecimal(lon)));
    }

    private InventoryBatch batch(String sku, Warehouse w, int qty, Integer expiresInDays) {
        return batches.save(new InventoryBatch(sku, w.getId(), qty, expiresInDays == null ? null : LocalDate.now().plusDays(expiresInDays)));
    }

    private static String sku() {
        return "SKU-" + UUID.randomUUID();
    }

    private ResultActions fulfil(String key, String sku, int quantity) throws Exception {
        return mockMvc.perform(post("/api/fulfilments").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"" + sku + "\",\"quantity\":" + quantity
                        + ",\"deliveryLat\":6.9271,\"deliveryLon\":79.8612,\"customerTier\":\"PRIORITY\"}"));
    }

    private int available(UUID batchId) {
        return batches.findById(batchId).orElseThrow().getQtyAvailable();
    }

    @Test
    void allocates_201_decrementsTheStock_andWritesTheLines() throws Exception {
        String sku = sku();
        Warehouse colombo = warehouse("Colombo", "6.9271", "79.8612");
        Warehouse kandy = warehouse("Kandy", "7.2906", "80.6337");
        InventoryBatch near = batch(sku, colombo, 50, 30);
        InventoryBatch far = batch(sku, kandy, 50, 30);

        String body = fulfil("k-" + UUID.randomUUID(), sku, 20)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.strategy").value("NEAREST"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].batchId").value(near.getId().toString()))
                .andExpect(jsonPath("$.lines[0].warehouseId").value(colombo.getId().toString()))
                .andExpect(jsonPath("$.lines[0].quantity").value(20))
                .andReturn().getResponse().getContentAsString();

        assertThat(available(near.getId())).isEqualTo(30);
        assertThat(available(far.getId())).isEqualTo(50);
        // and it can be read back
        String id = json.readTree(body).get("id").asText();
        mockMvc.perform(get("/api/fulfilments/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(20));
    }

    @Test
    void notEnoughStock_409_storesTheFailedOrder_andLeavesTheStockAlone() throws Exception {
        String sku = sku();
        InventoryBatch only = batch(sku, warehouse("Colombo", "6.9271", "79.8612"), 5, 30);

        fulfil("k-" + UUID.randomUUID(), sku, 6)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.containsString("Insufficient stock")))
                .andExpect(jsonPath("$.lines.length()").value(0));

        assertThat(available(only.getId())).isEqualTo(5);
    }

    @Test
    void expiredAndNearlyExpiredStock_isNeverShipped() throws Exception {
        String sku = sku();
        Warehouse colombo = warehouse("Colombo", "6.9271", "79.8612");
        InventoryBatch expired = batch(sku, colombo, 100, -3);
        InventoryBatch nearlyExpired = batch(sku, colombo, 100, 1);

        fulfil("k-" + UUID.randomUUID(), sku, 1).andExpect(status().isConflict());

        assertThat(available(expired.getId())).isEqualTo(100);
        assertThat(available(nearlyExpired.getId())).isEqualTo(100);
    }

    @Test
    void unknownSku_409() throws Exception {
        fulfil("k-" + UUID.randomUUID(), sku(), 1).andExpect(status().isConflict());
    }

    @Test
    void retryWithTheSameKey_returnsTheStoredOrder_andDecrementsOnce() throws Exception {
        String sku = sku();
        InventoryBatch only = batch(sku, warehouse("Colombo", "6.9271", "79.8612"), 50, 30);
        String key = "k-" + UUID.randomUUID();

        String first = fulfil(key, sku, 10).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = fulfil(key, sku, 10).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second).get("id")).isEqualTo(json.readTree(first).get("id"));
        assertThat(available(only.getId())).isEqualTo(40);
    }

    @Test
    void retryOfARejectedOrder_getsTheSame409() throws Exception {
        String sku = sku();
        batch(sku, warehouse("Colombo", "6.9271", "79.8612"), 1, 30);
        String key = "k-" + UUID.randomUUID();

        fulfil(key, sku, 5).andExpect(status().isConflict());
        fulfil(key, sku, 5).andExpect(status().isConflict()).andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void sameKeyDifferentRequest_is422() throws Exception {
        String sku = sku();
        batch(sku, warehouse("Colombo", "6.9271", "79.8612"), 50, 30);
        String key = "k-" + UUID.randomUUID();

        fulfil(key, sku, 10).andExpect(status().isCreated());
        fulfil(key, sku, 11).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidRequests_are400() throws Exception {
        mockMvc.perform(post("/api/fulfilments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"A\",\"quantity\":1,\"deliveryLat\":1,\"deliveryLon\":1,\"customerTier\":\"STANDARD\"}"))
                .andExpect(status().isBadRequest());                       // no Idempotency-Key
        mockMvc.perform(post("/api/fulfilments").header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"A\",\"quantity\":0,\"deliveryLat\":1,\"deliveryLon\":1,\"customerTier\":\"STANDARD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("quantity must be at least 1"));
        mockMvc.perform(post("/api/fulfilments").header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"A\",\"quantity\":1,\"deliveryLat\":1,\"deliveryLon\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("customerTier is required"));
        mockMvc.perform(post("/api/fulfilments").header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"A\",\"quantity\":1,\"deliveryLat\":1,\"deliveryLon\":1,\"customerTier\":\"GOLD\"}"))
                .andExpect(status().isBadRequest());
    }
}
