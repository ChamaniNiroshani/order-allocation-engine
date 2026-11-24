package com.chamani.order_allocation_engine;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.chamani.order_allocation_engine.controller.OrderController;
import com.chamani.order_allocation_engine.exception.GlobalExceptionHandler;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import com.chamani.order_allocation_engine.service.OrderQueryService;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "The database is unavailable" is a temporary condition: 503 + Retry-After and ONE short WARN line, not a 500 with a stack
 * trace per request (what the chaos run showed before the fix; see docs/chaos.md).
 */
@WebMvcTest(OrderController.class)
@Import(GlobalExceptionHandler.class)
class DatabaseUnavailableHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderAllocationFacade facade;

    @MockitoBean
    private OrderQueryService orderQueryService;

    /** Every way the stack reports "no database": no pooled connection in time, a lost connection, the raw JDBC form. */
    static Stream<Throwable> databaseDown() {
        SQLException hikariTimeout = new SQLTransientConnectionException(
                "HikariPool-1 - Connection is not available, request timed out after 3000ms");
        return Stream.of(
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction", hikariTimeout),
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", hikariTimeout),
                new DataAccessResourceFailureException("Unable to commit against JDBC Connection",
                        new SQLException("FATAL: terminating connection due to administrator command", "57P01")),
                new JDBCConnectionException("could not execute statement", new SQLException("An I/O error occurred", "08006")),
                hikariTimeout,
                // Requests IN FLIGHT when the database stops fail inside a wrapper Spring has no specific type for
                // (seen in the chaos run as 500s): the SQLSTATE deeper in the cause chain says "database gone".
                new JpaSystemException(new RuntimeException("could not execute statement",
                        new SQLException("FATAL: terminating connection due to administrator command", "57P01"))),
                new JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection",
                        new SQLException("This connection has been closed.", "08003"))),
                // what the rollback after a lost connection really looks like: the pool's proxy, no SQLSTATE
                new JpaSystemException(new RuntimeException("Unable to rollback against JDBC Connection",
                        new SQLException("Connection is closed"))));
    }

    @Test
    void anUnrelatedDatabaseError_isStillAnUnexpected500() throws Exception {
        when(facade.allocate(any(), any(), anyString())).thenThrow(new JpaSystemException(new RuntimeException(
                "could not execute statement", new SQLException("duplicate key value violates unique constraint", "23505"))));

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "k-other")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + UUID.randomUUID() + "\",\"quantityRequested\":1}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }

    @ParameterizedTest
    @MethodSource("databaseDown")
    void databaseUnavailable_returns503WithRetryAfter(Throwable failure) throws Exception {
        when(facade.allocate(any(), any(), anyString())).thenAnswer(invocation -> { throw failure; });

        mockMvc.perform(post("/api/orders")
                        .header("Idempotency-Key", "k-db")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":\"" + UUID.randomUUID() + "\",\"quantityRequested\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("The service is temporarily unavailable. Please retry shortly."));
    }

    @Test
    void aBurstOfFailures_logsOneWarnWithoutStackTrace_andNothingAtError() {
        Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
        try {
            GlobalExceptionHandler handler = new GlobalExceptionHandler(5);   // a fresh handler: its rate limit starts clean
            var failure = new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                    new SQLTransientConnectionException("HikariPool-1 - Connection is not available, request timed out after 3000ms"));

            for (int i = 0; i < 50; i++) {
                assertThat(handler.handleDatabaseUnavailable(failure).getStatusCode().value()).isEqualTo(503);
            }

            assertThat(appender.list).hasSize(1);
            ILoggingEvent line = appender.list.get(0);
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getThrowableProxy()).as("no stack trace").isNull();
            assertThat(line.getFormattedMessage())
                    .contains("Database unavailable")
                    .contains("CannotCreateTransactionException")
                    .contains("request timed out after 3000ms");
        } finally {
            handlerLogger.detachAppender(appender);
        }
    }
}
