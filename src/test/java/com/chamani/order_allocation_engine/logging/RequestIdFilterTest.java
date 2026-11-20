package com.chamani.order_allocation_engine.logging;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
    }

    private static final String UUID_SHAPE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    /** Runs the filter and returns the request id the rest of the chain saw in the MDC. */
    private String run(String suppliedId, MockHttpServletResponse response) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (suppliedId != null) {
            request.addHeader(RequestIdFilter.HEADER, suppliedId);
        }
        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));
        filter.doFilter(request, response, chain);
        return seenInChain.get();
    }

    @Test
    void noHeader_generatesAnId_putsItInTheMdc_andReturnsIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run(null, response);

        assertTrue(seen.matches(UUID_SHAPE), seen);
        assertEquals(seen, response.getHeader(RequestIdFilter.HEADER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "A", "0f8fad5b-d9cb-469f-a165-70867728950e", "UPPER-and-lower-123"})
    void aSafeIncomingId_isKept_andEchoed(String id) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals(id, run(id, response));
        assertEquals(id, response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void anIdOfExactly64Characters_isKept() throws Exception {
        String id = "a".repeat(64);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertEquals(id, run(id, response));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",                                   // empty
            "   ",                                // blank
            "has space",
            "semi;colon",
            "under_score",
            "dot.dot",
            "café",                          // not ASCII
            "id\nINFO forged log line",           // log injection: a newline would start a fake entry
            "id\r\nX-Injected: yes",
            "tab\tid",
            "${jndi:ldap://evil}",
    })
    void anUnsafeIncomingId_isReplaced(String bad) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run(bad, response);

        assertNotEquals(bad, seen);
        assertTrue(seen.matches(UUID_SHAPE), "replaced by a fresh UUID: " + seen);
        assertEquals(seen, response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void anIdOf65Characters_isReplaced() throws Exception {
        String tooLong = "a".repeat(65);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run(tooLong, response);

        assertTrue(seen.matches(UUID_SHAPE), seen);
    }

    @Test
    void theMdcIsCleared_whenTheRequestEnds() throws Exception {
        MDC.put(OrderMdc.ORDER_ID, "left-over-order");                  // something a handler forgot to remove

        run("req-1", new MockHttpServletResponse());

        assertNull(MDC.get(RequestIdFilter.MDC_KEY));
        assertNull(MDC.get(OrderMdc.ORDER_ID));
    }

    @Test
    void theMdcIsCleared_evenWhenTheRequestFails() {
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("boom");
        };

        assertThrows(IllegalStateException.class,
                () -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failing));

        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "a failed request must not leave its id on the thread");
    }

    @Test
    void theHeaderIsSetBeforeTheChainRuns_soAnErrorResponseCarriesIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> headerWhenChainRan = new AtomicReference<>();
        FilterChain chain = (req, res) -> headerWhenChainRan.set(((MockHttpServletResponse) res).getHeader(RequestIdFilter.HEADER));

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertTrue(headerWhenChainRan.get() != null, "header must already be set while the handler runs");
    }

    @Test
    void orderMdcScope_withNoOrderId_doesNothing_insteadOfFailingTheRequest() {
        try (var ignored = OrderMdc.put(null)) {
            assertNull(MDC.get(OrderMdc.ORDER_ID));
        }
        assertNull(MDC.get(OrderMdc.ORDER_ID));
    }

    @Test
    void orderMdcScope_putsTheOrderId_thenRestoresWhatWasThere() {
        MDC.put(OrderMdc.ORDER_ID, "outer");

        try (var ignored = OrderMdc.put(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"))) {
            assertEquals("00000000-0000-0000-0000-000000000001", MDC.get(OrderMdc.ORDER_ID));
        }

        assertEquals("outer", MDC.get(OrderMdc.ORDER_ID), "an outer scope's id comes back");
        MDC.remove(OrderMdc.ORDER_ID);
        try (var ignored = OrderMdc.put(java.util.UUID.randomUUID())) {
            assertTrue(MDC.get(OrderMdc.ORDER_ID) != null);
        }
        assertNull(MDC.get(OrderMdc.ORDER_ID), "with nothing outside, nothing is left behind");
    }
}
