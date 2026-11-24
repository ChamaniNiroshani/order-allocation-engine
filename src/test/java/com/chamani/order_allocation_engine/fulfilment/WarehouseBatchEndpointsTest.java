package com.chamani.order_allocation_engine.fulfilment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.chamani.order_allocation_engine.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/warehouses, POST /api/batches, GET /api/fulfilments/{id} and the schema constraints behind them. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class WarehouseBatchEndpointsTest {

    @Autowired MockMvc mockMvc;
    @Autowired WarehouseRepository warehouses;
    @Autowired InventoryBatchRepository batches;
    @Autowired FulfilmentOrderRepository orders;
    @Autowired FulfilmentLineRepository lines;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private String createWarehouse(String name) throws Exception {
        String body = mockMvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"lat\":6.9271,\"lon\":79.8612}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.lat").value(6.9271))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    @Test
    void createBatch_returns201AndKeepsTheExpiryDate() throws Exception {
        String warehouseId = createWarehouse("Colombo");
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"MILK\",\"warehouseId\":\"" + warehouseId + "\",\"qtyAvailable\":40,\"expiryDate\":\"2030-01-31\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.warehouseId").value(warehouseId))
                .andExpect(jsonPath("$.qtyAvailable").value(40))
                .andExpect(jsonPath("$.qtyReserved").value(0))
                .andExpect(jsonPath("$.expiryDate").value("2030-01-31"));
    }

    @Test
    void createBatch_withoutExpiryDate_isAllowed() throws Exception {
        String warehouseId = createWarehouse("Kandy");
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"RICE\",\"warehouseId\":\"" + warehouseId + "\",\"qtyAvailable\":5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiryDate").doesNotExist());
    }

    @Test
    void createBatch_unknownWarehouse_is404() throws Exception {
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"MILK\",\"warehouseId\":\"" + UUID.randomUUID() + "\",\"qtyAvailable\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createBatch_negativeQuantity_is400() throws Exception {
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"MILK\",\"warehouseId\":\"" + UUID.randomUUID() + "\",\"qtyAvailable\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("qtyAvailable must not be negative"));
    }

    @Test
    void createBatch_blankSku_is400() throws Exception {
        mockMvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\" \",\"warehouseId\":\"" + UUID.randomUUID() + "\",\"qtyAvailable\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWarehouse_latitudeOutOfRange_is400() throws Exception {
        mockMvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nowhere\",\"lat\":91,\"lon\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("lat must be between -90 and 90"));
    }

    @Test
    void createWarehouse_missingName_is400() throws Exception {
        mockMvc.perform(post("/api/warehouses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":1,\"lon\":1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getFulfilment_unknownId_is404() throws Exception {
        mockMvc.perform(get("/api/fulfilments/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getFulfilment_returnsTheOrderWithItsLines() throws Exception {
        Warehouse w = warehouses.save(new Warehouse("Galle", new BigDecimal("6.053500"), new BigDecimal("80.221000")));
        InventoryBatch batch = batches.save(new InventoryBatch("SKU-" + UUID.randomUUID(), w.getId(), 10, LocalDate.now().plusDays(30)));
        FulfilmentOrder order = new FulfilmentOrder(batch.getSku(), 4, new BigDecimal("6.9271"), new BigDecimal("79.8612"),
                CustomerTier.PRIORITY, StrategyType.NEAREST, "key-" + UUID.randomUUID(), null);
        order.markAllocated();
        order = orders.save(order);
        lines.save(new FulfilmentLine(order.getId(), batch.getId(), w.getId(), 4));

        mockMvc.perform(get("/api/fulfilments/" + order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.customerTier").value("PRIORITY"))
                .andExpect(jsonPath("$.strategy").value("NEAREST"))
                .andExpect(jsonPath("$.quantity").value(4))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].batchId").value(batch.getId().toString()))
                .andExpect(jsonPath("$.lines[0].warehouseId").value(w.getId().toString()))
                .andExpect(jsonPath("$.lines[0].quantity").value(4));
    }

    @Test
    void database_refusesNegativeStockAndDuplicateIdempotencyKeys() {
        Warehouse w = warehouses.save(new Warehouse("Test", BigDecimal.ONE, BigDecimal.ONE));
        UUID batchId = batches.save(new InventoryBatch("S-" + UUID.randomUUID(), w.getId(), 1, null)).getId();

        assertThatThrownBy(() -> jdbc.update("UPDATE inventory_batch SET qty_available = -1 WHERE id = ?", batchId))
                .isInstanceOf(DataIntegrityViolationException.class);

        String key = "dup-" + UUID.randomUUID();
        orders.save(new FulfilmentOrder("S", 1, BigDecimal.ONE, BigDecimal.ONE, CustomerTier.STANDARD, StrategyType.NEAREST, key, null));
        assertThatThrownBy(() -> orders.saveAndFlush(
                new FulfilmentOrder("S", 1, BigDecimal.ONE, BigDecimal.ONE, CustomerTier.STANDARD, StrategyType.NEAREST, key, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(orders.findByIdempotencyKey(key)).isPresent();
    }
}
