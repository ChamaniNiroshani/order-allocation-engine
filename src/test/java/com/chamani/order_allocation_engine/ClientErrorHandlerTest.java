package com.chamani.order_allocation_engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chamani.order_allocation_engine.controller.OrderController;
import com.chamani.order_allocation_engine.exception.GlobalExceptionHandler;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import com.chamani.order_allocation_engine.service.OrderQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Client mistakes (unknown URL, wrong verb, bad body) are 4xx with the usual body and never reach the ERROR log. */
@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class ClientErrorHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderAllocationFacade facade;

    @MockitoBean
    private OrderQueryService orderQueryService;

    private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        handlerLogger.detachAppender(appender);
    }

    private void assertNothingLoggedAtErrorOrWarn() {
        assertThat(appender.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN));
    }

    @Test
    void unknownUrl_returns404() throws Exception {
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        assertNothingLoggedAtErrorOrWarn();
    }

    @Test
    void wrongHttpMethod_returns405() throws Exception {
        mockMvc.perform(delete("/api/orders"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        assertNothingLoggedAtErrorOrWarn();
    }

    @Test
    void malformedJsonBody_returns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
        assertNothingLoggedAtErrorOrWarn();
    }

    @Test
    void genuinelyUnexpectedException_stillReturns500AndLogsError() throws Exception {
        when(facade.allocate(any(), any(), anyString())).thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "k-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + java.util.UUID.randomUUID() + "\",\"quantityRequested\":1}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR);
    }
}
