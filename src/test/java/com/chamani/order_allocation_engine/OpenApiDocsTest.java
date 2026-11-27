package com.chamani.order_allocation_engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The published API description. The prod profile is active because the public demo serves it with that profile. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("prod")
class OpenApiDocsTest {

    @Autowired
    private MockMvc mockMvc;

    private JsonNode apiDocs() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(json);
    }

    /** Every endpoint, with its tag and exactly the status codes it can return. A new code has to be added here on purpose. */
    @Test
    void everyEndpointIsDescribed_withItsTagAndOnlyItsOwnStatusCodes() throws Exception {
        Map<String, String> expected = new TreeMap<>(Map.ofEntries(
                Map.entry("post /api/orders", "orders [201, 400, 404, 409, 415, 422, 503]"),
                Map.entry("get /api/orders/{id}", "orders [200, 400, 404, 503]"),
                Map.entry("get /api/orders/{id}/history", "orders [200, 400, 404, 503]"),
                Map.entry("post /api/checkout", "checkout [201, 202, 400, 402, 404, 409, 415, 422, 502, 503]"),
                Map.entry("post /api/fulfilments", "fulfilment [201, 400, 409, 415, 422, 503]"),
                Map.entry("get /api/fulfilments/{id}", "fulfilment [200, 400, 404, 503]"),
                Map.entry("post /api/warehouses", "fulfilment [201, 400, 415, 503]"),
                Map.entry("post /api/batches", "fulfilment [201, 400, 404, 415, 503]"),
                Map.entry("post /api/inventory", "inventory [201, 400, 415, 503]"),
                Map.entry("get /api/inventory/{id}", "inventory [200, 400, 404, 503]")));

        Map<String, String> actual = new TreeMap<>();
        apiDocs().get("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(operation -> {
            Set<String> codes = new TreeSet<>();
            operation.getValue().get("responses").fieldNames().forEachRemaining(codes::add);
            actual.put(operation.getKey() + " " + path.getKey(),
                    operation.getValue().get("tags").get(0).asText() + " " + codes);
        }));

        assertEquals(expected, actual);
    }

    @Test
    void infoLinksTheRepository_andSharedErrorResponsesResolve() throws Exception {
        JsonNode docs = apiDocs();

        assertEquals("Order Allocation Engine", docs.at("/info/title").asText());
        assertTrue(docs.at("/info/description").asText().contains("github.com/ChamaniNiroshani/order-allocation-engine"));
        assertTrue(docs.at("/info/description").asText().contains("behind authentication"));
        assertEquals("#/components/responses/ServiceUnavailable",
                docs.at("/paths/~1api~1orders/post/responses/503/$ref").asText());
        assertTrue(docs.at("/components/responses/ServiceUnavailable/headers/Retry-After").isObject());
        assertTrue(docs.at("/components/schemas/ApiError/properties/message").isObject());
        assertEquals("#/components/schemas/OrderResponse",
                docs.at("/paths/~1api~1checkout/post/responses/402/content/application~1json/schema/$ref").asText());
    }

    @Test
    void swaggerUiIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
