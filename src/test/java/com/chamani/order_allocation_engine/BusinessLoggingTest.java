package com.chamani.order_allocation_engine;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.chamani.order_allocation_engine.logging.OrderMdc;
import com.chamani.order_allocation_engine.logging.RequestIdFilter;
import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Request ids on the way in and out, and the business events with the context they are logged under.
 * Same annotations as the other default-context tests, so it shares their Spring context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class BusinessLoggingTest {

    private static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private Environment environment;

    private UUID newItem(int stock) {
        return inventoryItemRepository.save(new InventoryItem(stock)).getItemId();
    }

    private ResultActions send(String path, UUID itemId, int quantity, String key, String requestId) throws Exception {
        var request = post(path)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + quantity + "}");
        if (requestId != null) {
            request.header(RequestIdFilter.HEADER, requestId);
        }
        return mockMvc.perform(request);
    }

    // ---- X-Request-Id through the real filter chain ---------------------------------------------------------

    @Test
    void everyResponse_hasARequestId() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern(UUID_SHAPE)));
    }

    @Test
    void aValidIncomingId_isEchoedBack_andIsOnTheBusinessLogLines() throws Exception {
        UUID itemId = newItem(5);
        try (LogCapture logs = new LogCapture()) {
            send("/api/orders", itemId, 1, "bl-" + UUID.randomUUID(), "gateway-req-42")
                    .andExpect(status().isCreated())
                    .andExpect(header().string(RequestIdFilter.HEADER, "gateway-req-42"));

            ILoggingEvent event = logs.events("order.allocated").get(0);
            assertEquals("gateway-req-42", LogCapture.mdc(event).get(RequestIdFilter.MDC_KEY), "the request id is on the log line");
        }
    }

    @Test
    void anInvalidIncomingId_isReplacedByAFreshOne() throws Exception {
        UUID itemId = newItem(5);
        String forged = "x y;INFO forged";
        var result = send("/api/orders", itemId, 1, "bl-" + UUID.randomUUID(), forged)
                .andExpect(status().isCreated())
                .andReturn();

        String returned = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertNotEquals(forged, returned);
        assertTrue(returned.matches(UUID_SHAPE), returned);
    }

    @Test
    void anErrorResponse_carriesTheRequestIdToo() throws Exception {
        mockMvc.perform(get("/api/orders/" + UUID.randomUUID()).header(RequestIdFilter.HEADER, "err-1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(RequestIdFilter.HEADER, "err-1"));
    }

    // ---- business events -----------------------------------------------------------------------------------

    @Test
    void directFlow_logsAllocatedAndRejected_withStructuredFields_andTheOrderIdInTheContext() throws Exception {
        UUID itemId = newItem(2);
        try (LogCapture logs = new LogCapture()) {
            send("/api/orders", itemId, 2, "bl-" + UUID.randomUUID(), null).andExpect(status().isCreated());
            send("/api/orders", itemId, 1, "bl-" + UUID.randomUUID(), null).andExpect(status().isConflict());

            assertEquals(java.util.List.of("order.allocated", "order.rejected"), logs.eventNames());

            ILoggingEvent allocated = logs.events("order.allocated").get(0);
            assertEquals("direct", LogCapture.field(allocated, "flow"));
            assertEquals(itemId.toString(), LogCapture.field(allocated, "itemId"));
            assertEquals("2", LogCapture.field(allocated, "quantity"));
            assertEquals("ALLOCATED", LogCapture.field(allocated, "status"));
            assertTrue(LogCapture.mdc(allocated).get(OrderMdc.ORDER_ID).matches(UUID_SHAPE), "the order id is in the log context");
            assertEquals("Order allocated", allocated.getFormattedMessage(), "the message is a fixed sentence, values are fields");

            ILoggingEvent rejected = logs.events("order.rejected").get(0);
            assertEquals("FAILED", LogCapture.field(rejected, "status"));
            assertTrue(LogCapture.field(rejected, "reason").startsWith("Insufficient inventory"));
        }
        assertNull(MDC.get(OrderMdc.ORDER_ID), "nothing is left on the thread");
    }

    @Test
    void checkoutFlow_logsAllocatedAndRejected_withTheOrderIdInTheContext() throws Exception {
        UUID itemId = newItem(1);
        try (LogCapture logs = new LogCapture()) {
            send("/api/checkout", itemId, 1, "bl-" + UUID.randomUUID(), null).andExpect(status().isCreated());
            send("/api/checkout", itemId, 1, "bl-" + UUID.randomUUID(), null).andExpect(status().isConflict());

            assertEquals(java.util.List.of("order.allocated", "order.rejected"), logs.eventNames());
            for (ILoggingEvent event : java.util.List.of(logs.events("order.allocated").get(0), logs.events("order.rejected").get(0))) {
                assertEquals("checkout", LogCapture.field(event, "flow"));
                assertTrue(LogCapture.mdc(event).get(OrderMdc.ORDER_ID).matches(UUID_SHAPE), "the order id is in the log context");
            }
        }
        assertNull(MDC.get(OrderMdc.ORDER_ID), "nothing is left on the thread");
    }

    @Test
    void aRetryOfAFinishedOrder_logsNoNewBusinessEvent() throws Exception {
        UUID itemId = newItem(5);
        String key = "bl-" + UUID.randomUUID();
        send("/api/orders", itemId, 1, key, null).andExpect(status().isCreated());
        try (LogCapture logs = new LogCapture()) {
            send("/api/orders", itemId, 1, key, null).andExpect(status().isCreated());
            send("/api/checkout", itemId, 1, "bl-" + UUID.randomUUID(), null).andExpect(status().isCreated());
            assertEquals(java.util.List.of("order.allocated"), logs.eventNames(),
                    "the replay allocated nothing, so it logs nothing; only the new checkout order does");
        }
    }

    @Test
    void aCleanupRun_isOneLine_notOnePerOrder() {
        try (LogCapture logs = new LogCapture()) {
            com.chamani.order_allocation_engine.logging.OrderEvents.reservationsCleanedUp(250, 3);

            assertEquals(java.util.List.of("reservations.cleanup"), logs.eventNames());
            ILoggingEvent event = logs.events("reservations.cleanup").get(0);
            assertEquals("250", LogCapture.field(event, "expired"));
            assertEquals("3", LogCapture.field(event, "confirmed"));
        }
    }

    @Test
    void theIdempotencyKey_isNeverWrittenToTheBusinessLog() throws Exception {
        UUID itemId = newItem(5);
        String key = "secret-key-" + UUID.randomUUID();
        try (LogCapture logs = new LogCapture()) {
            send("/api/orders", itemId, 1, key, null).andExpect(status().isCreated());
            for (ILoggingEvent event : logs.events("order.allocated")) {
                assertFalse(event.getKeyValuePairs().toString().contains(key));
                assertFalse(event.getFormattedMessage().contains(key));
            }
        }
    }

    // ---- configuration -------------------------------------------------------------------------------------

    @Test
    void sqlLogging_isOffByDefault_andOnlyTheDevProfileTurnsItOn() {
        assertFalse(Boolean.parseBoolean(environment.getProperty("spring.jpa.show-sql", "false")));
        assertEquals(java.util.List.of(), java.util.Arrays.asList(environment.getActiveProfiles()), "tests run without a profile");
    }
}
