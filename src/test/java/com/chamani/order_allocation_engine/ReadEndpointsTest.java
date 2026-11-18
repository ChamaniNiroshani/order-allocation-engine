package com.chamani.order_allocation_engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /api/inventory, GET /api/inventory/{id}, GET /api/orders/{id}. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReadEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String createItem(int quantity) throws Exception {
        String body = mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantityAvailable\":" + quantity + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("itemId").asText();
    }

    @Test
    void createInventory_returns201WithDto() throws Exception {
        mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantityAvailable\":25}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemId").exists())
                .andExpect(jsonPath("$.quantityAvailable").value(25))
                .andExpect(jsonPath("$.quantityReserved").value(0))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void createInventory_zeroStockIsAllowed() throws Exception {
        mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantityAvailable\":0}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantityAvailable").value(0));
    }

    @Test
    void createInventory_negativeQuantity_returns400() throws Exception {
        mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantityAvailable\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("quantityAvailable must not be negative"));
    }

    @Test
    void createInventory_missingQuantity_returns400() throws Exception {
        mockMvc.perform(post("/api/inventory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getInventory_returns200() throws Exception {
        String itemId = createItem(40);

        mockMvc.perform(get("/api/inventory/" + itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(itemId))
                .andExpect(jsonPath("$.quantityAvailable").value(40));
    }

    @Test
    void getInventory_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/inventory/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getInventory_malformedId_returns400() throws Exception {
        mockMvc.perform(get("/api/inventory/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOrder_returns200_forAllocatedAndFailedOrders() throws Exception {
        String itemId = createItem(10);

        String allocatedBody = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "read-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + itemId + "\",\"quantityRequested\":10}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String failedBody = mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "read-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + itemId + "\",\"quantityRequested\":1}"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        String allocatedId = objectMapper.readTree(allocatedBody).get("orderId").asText();
        String failedId = objectMapper.readTree(failedBody).get("orderId").asText();

        mockMvc.perform(get("/api/orders/" + allocatedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(allocatedId))
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.quantityAllocated").value(10));

        mockMvc.perform(get("/api/orders/" + failedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.quantityAllocated").value(0))
                .andExpect(jsonPath("$.reason").exists());

        // stock reflects the allocation
        mockMvc.perform(get("/api/inventory/" + itemId))
                .andExpect(jsonPath("$.quantityAvailable").value(0));
    }

    @Test
    void getOrder_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/orders/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
