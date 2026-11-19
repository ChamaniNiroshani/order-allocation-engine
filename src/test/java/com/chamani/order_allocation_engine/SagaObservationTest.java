package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.payment.FakePaymentGateway;
import com.chamani.order_allocation_engine.payment.PaymentResult;
import com.chamani.order_allocation_engine.payment.PaymentTimeoutException;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The span tree a checkout produces, checked without Jaeger and without any exporter: a handler on the context's
 * ObservationRegistry records every observation (each one is a span when tracing is on, which production has).
 * The point is the SHAPE: http -> saga step -> payment gateway call, with the JDBC work underneath its step.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SagaObservationTest.RecordingConfig.class})
class SagaObservationTest {

    /** One observation as the handler saw it. */
    static final class Recorded {
        final String name;
        final Recorded parent;
        final Map<String, String> lowCardinality = new java.util.TreeMap<>();
        final Map<String, String> highCardinality = new java.util.TreeMap<>();
        volatile Throwable error;
        volatile boolean stopped;

        Recorded(String name, Recorded parent) {
            this.name = name;
            this.parent = parent;
        }

        boolean hasAncestor(String ancestorName) {
            for (Recorded p = parent; p != null; p = p.parent) {
                if (p.name.equals(ancestorName)) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        final List<Recorded> all = Collections.synchronizedList(new ArrayList<>());
        private final Map<Object, Recorded> byContext = Collections.synchronizedMap(new IdentityHashMap<>());

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStart(Observation.Context context) {
            ObservationView parentObservation = context.getParentObservation();
            Recorded parent = parentObservation == null ? null : byContext.get(parentObservation.getContextView());
            Recorded recorded = new Recorded(context.getName(), parent);
            byContext.put(context, recorded);
            all.add(recorded);
        }

        @Override
        public void onError(Observation.Context context) {
            Recorded recorded = byContext.get(context);
            if (recorded != null) {
                recorded.error = context.getError();
            }
        }

        @Override
        public void onStop(Observation.Context context) {
            Recorded recorded = byContext.remove(context);
            if (recorded != null) {
                context.getLowCardinalityKeyValues().forEach(kv -> recorded.lowCardinality.put(kv.getKey(), kv.getValue()));
                context.getHighCardinalityKeyValues().forEach(kv -> recorded.highCardinality.put(kv.getKey(), kv.getValue()));
                recorded.stopped = true;
            }
        }

        void clear() {
            all.clear();
        }

        List<Recorded> named(String name) {
            synchronized (all) {
                return all.stream().filter(r -> r.name.equals(name)).toList();
            }
        }

        Recorded only(String name) {
            List<Recorded> found = named(name);
            assertEquals(1, found.size(), "expected exactly one '" + name + "' but saw " + names());
            return found.get(0);
        }

        List<String> names() {
            synchronized (all) {
                return all.stream().map(r -> r.name).toList();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingConfig {
        /** Spring Boot registers every ObservationHandler bean on the ObservationRegistry. */
        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Autowired
    private RecordingHandler handler;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoSpyBean
    private FakePaymentGateway gateway;

    @BeforeEach
    void clear() {
        handler.clear();
    }

    private UUID newItem(int stock) {
        return inventoryItemRepository.save(new InventoryItem(stock)).getItemId();
    }

    private ResultActions checkout(UUID itemId, int quantity, String key) throws Exception {
        return mockMvc.perform(post("/api/checkout")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"" + itemId + "\",\"quantityRequested\":" + quantity + "}"));
    }

    @Test
    void successfulCheckout_isHttp_thenReserveChargeConfirm_withTheGatewayCallUnderCharge() throws Exception {
        UUID itemId = newItem(5);

        checkout(itemId, 1, "obs-" + UUID.randomUUID()).andExpect(status().isCreated());

        Recorded reserve = handler.only("saga.reserve");
        Recorded charge = handler.only("saga.charge");
        Recorded confirm = handler.only("saga.confirm");
        Recorded gatewayCall = handler.only("payment.gateway.charge");
        assertEquals(0, handler.named("saga.compensate").size());
        assertEquals(0, handler.named("payment.gateway.refund").size());

        // HTTP request -> saga step
        for (Recorded step : List.of(reserve, charge, confirm)) {
            assertNotNull(step.parent, step.name + " must be inside the request");
            assertEquals("http.server.requests", step.parent.name, step.name);
        }
        // saga.charge -> payment gateway call
        assertEquals("saga.charge", gatewayCall.parent.name);
        assertTrue(gatewayCall.hasAncestor("http.server.requests"));
    }

    @Test
    void theDatabaseWork_isUnderTheStepThatDidIt() throws Exception {
        UUID itemId = newItem(5);
        handler.clear();                                   // creating the item ran queries too, outside any request

        checkout(itemId, 1, "obs-" + UUID.randomUUID()).andExpect(status().isCreated());

        List<Recorded> queries = handler.named("jdbc.query");
        assertFalse(queries.isEmpty(), "JDBC queries must show up as observations; saw " + handler.names());
        assertTrue(queries.stream().anyMatch(q -> q.hasAncestor("saga.reserve")), "the reservation's SQL is under saga.reserve");
        assertTrue(queries.stream().anyMatch(q -> q.hasAncestor("saga.confirm")), "the confirmation's SQL is under saga.confirm");
        assertTrue(queries.stream().anyMatch(q -> q.hasAncestor("saga.charge")), "recording the payment is under saga.charge");
        assertTrue(queries.stream().allMatch(q -> q.hasAncestor("http.server.requests")), "no query outside a request");
        assertTrue(handler.named("jdbc.result-set").isEmpty(), "result-set fetches are deliberately not traced");
    }

    @Test
    void declinedCheckout_compensates_andNeverRefunds() throws Exception {
        UUID itemId = newItem(5);
        doReturn(PaymentResult.declined("test decline")).when(gateway).charge(any(), any(), any());

        checkout(itemId, 1, "obs-" + UUID.randomUUID()).andExpect(status().isPaymentRequired());

        Recorded compensate = handler.only("saga.compensate");
        assertEquals("http.server.requests", compensate.parent.name);
        assertEquals(0, handler.named("saga.confirm").size());
        assertEquals(0, handler.named("payment.gateway.refund").size(), "nothing was charged, nothing to refund");
        assertTrue(handler.named("jdbc.query").stream().anyMatch(q -> q.hasAncestor("saga.compensate")),
                "releasing the stock is database work under saga.compensate");
        assertEquals(null, handler.only("saga.charge").error, "a decline is an answer, not an error");
    }

    @Test
    void timedOutCheckout_marksTheChargeFailed_thenRefundsInsideCompensate() throws Exception {
        UUID itemId = newItem(5);
        doThrow(new PaymentTimeoutException("k")).when(gateway).charge(any(), any(), any());

        checkout(itemId, 1, "obs-" + UUID.randomUUID()).andExpect(status().isBadGateway());

        assertInstanceOf(PaymentTimeoutException.class, handler.only("payment.gateway.charge").error, "the gateway span carries the error");
        assertInstanceOf(PaymentTimeoutException.class, handler.only("saga.charge").error, "so does the step");
        Recorded refund = handler.only("payment.gateway.refund");
        assertEquals("saga.compensate", refund.parent.name, "the refund is part of compensating");
        assertEquals(null, handler.only("saga.compensate").error, "the compensation itself worked");
        assertEquals(0, handler.named("saga.confirm").size());
    }

    @Test
    void outOfStock_stopsAfterReserve() throws Exception {
        UUID itemId = newItem(1);

        checkout(itemId, 5, "obs-" + UUID.randomUUID()).andExpect(status().isConflict());

        assertEquals(1, handler.named("saga.reserve").size());
        assertEquals(List.of(), handler.named("saga.charge"));
        assertEquals(List.of(), handler.named("payment.gateway.charge"));
    }

    @Test
    void everyObservation_isStopped_evenWhenThePaymentFails() throws Exception {
        UUID itemId = newItem(5);
        doThrow(new PaymentTimeoutException("k")).when(gateway).charge(any(), any(), any());

        checkout(itemId, 1, "obs-" + UUID.randomUUID()).andExpect(status().isBadGateway());

        synchronized (handler.all) {
            for (Recorded r : handler.all) {
                assertTrue(r.stopped, r.name + " was started but never stopped (a leaked span)");
            }
        }
    }

    @Test
    void theOrderId_isOnTheSpan_butNeverOnAMetric() throws Exception {
        UUID itemId = newItem(5);
        String key = "obs-" + UUID.randomUUID();

        String body = checkout(itemId, 1, key).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String orderId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("orderId").asText();

        assertEquals(orderId, handler.only("saga.charge").highCardinality.get("order.id"), "findable from a trace");
        assertEquals(itemId.toString(), handler.only("saga.reserve").highCardinality.get("item.id"));
        for (Recorded r : handler.all) {
            assertFalse(r.lowCardinality.containsKey("order.id") || r.lowCardinality.containsKey("item.id"),
                    r.name + " must not make the order id a metric tag");
        }
        for (Meter meter : meterRegistry.getMeters()) {
            meter.getId().getTags().forEach(tag -> {
                assertFalse(tag.getValue().contains(orderId), meter.getId().getName() + " has the order id as a tag");
                assertFalse(tag.getValue().contains(itemId.toString()), meter.getId().getName() + " has the item id as a tag");
                assertFalse(tag.getValue().contains(key), meter.getId().getName() + " has the idempotency key as a tag");
            });
        }
    }

    @Test
    void jdbcParameterValues_areNotRecorded() throws Exception {
        UUID itemId = newItem(5);
        String key = "obs-secret-" + UUID.randomUUID();

        checkout(itemId, 1, key).andExpect(status().isCreated());

        for (Recorded r : handler.named("jdbc.query")) {
            assertFalse(r.highCardinality.toString().contains(key), "the idempotency key is a bind parameter and must not be on a span");
            assertFalse(r.highCardinality.toString().contains(itemId.toString()), "nor the item id");
        }
    }
}
