package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Integration tests for POST /api/orders (same database as the other integration tests). */
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    private static final String HEADER = "Idempotency-Key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static MockHttpServletRequestBuilder request(UUID itemId, int quantity) {
        return post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + quantity + "}");
    }

    private UUID newItem(int stock) {
        return inventoryItemRepository.save(new InventoryItem(stock)).getItemId();
    }

    @Test
    void firstCall_returns201() throws Exception {
        mockMvc.perform(request(newItem(100), 10).header(HEADER, "mvc-" + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ALLOCATED"));
    }

    @Test
    void sameKeyAndPayload_returnsSameOrderAndSameStatus() throws Exception {
        UUID itemId = newItem(100);
        String key = "mvc-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(request(itemId, 10).header(HEADER, key))
                .andExpect(status().isCreated()).andReturn();
        MvcResult second = mockMvc.perform(request(itemId, 10).header(HEADER, key))
                .andExpect(status().isCreated()).andReturn();

        assertEquals(first.getResponse().getStatus(), second.getResponse().getStatus());
        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        assertEquals(firstBody.get("orderId").asText(), secondBody.get("orderId").asText());
    }

    @Test
    void failedOrderReplay_returns409Both() throws Exception {
        UUID itemId = newItem(5);
        String key = "mvc-" + UUID.randomUUID();

        mockMvc.perform(request(itemId, 10).header(HEADER, key)).andExpect(status().isConflict());
        mockMvc.perform(request(itemId, 10).header(HEADER, key)).andExpect(status().isConflict());
    }

    @Test
    void sameKeyDifferentQuantity_returns422() throws Exception {
        UUID itemId = newItem(100);
        String key = "mvc-" + UUID.randomUUID();

        mockMvc.perform(request(itemId, 10).header(HEADER, key)).andExpect(status().isCreated());
        mockMvc.perform(request(itemId, 20).header(HEADER, key))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString(key)));
    }

    @Test
    void missingHeader_returns400() throws Exception {
        mockMvc.perform(request(newItem(100), 10))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"));
    }

    @Test
    void blankHeader_returns400() throws Exception {
        mockMvc.perform(request(newItem(100), 10).header(HEADER, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Idempotency-Key must not be blank and at most 255 characters"));
    }
}
