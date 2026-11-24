package com.chamani.order_allocation_engine.exception;

import org.hibernate.exception.JDBCConnectionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final int unavailableRetryAfterSeconds;
    /** At most one WARN line per 10 s for "database unavailable", with a count of the ones skipped. */
    private final RateLimitedWarn databaseWarn = new RateLimitedWarn(10_000);

    public GlobalExceptionHandler(@Value("${api.unavailable.retry-after-seconds:5}") int unavailableRetryAfterSeconds) {
        this.unavailableRetryAfterSeconds = unavailableRetryAfterSeconds;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Validation failed");
        return buildResponse(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<Map<String, Object>> handleKeyReused(IdempotencyKeyReusedException ex) {
        return buildResponse(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler(StockContentionException.class)
    public ResponseEntity<Map<String, Object>> handleContention(StockContentionException ex) {
        ResponseEntity<Map<String, Object>> response = buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(response.getBody());
    }

    /**
     * The database cannot be reached (down, restarting, or no connection within Hikari's connection-timeout): a temporary
     * condition, not a bug. 503 + Retry-After tells the client to try again, instead of a 500 with a stack trace per request.
     * Covers: no connection from the pool (CannotCreateTransactionException, CannotGetJdbcConnectionException), a connection
     * lost mid-request (DataAccessResourceFailureException, JDBCConnectionException) and the raw JDBC form.
     * Data is safe either way: stock and order changes commit together, so a failed request changed nothing.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            JDBCConnectionException.class, SQLTransientConnectionException.class})
    public ResponseEntity<Map<String, Object>> handleDatabaseUnavailable(Exception ex) {
        databaseWarn.warn(log, "Database unavailable, answering 503: {}: {}",
                ex.getClass().getSimpleName(), NestedExceptionUtils.getMostSpecificCause(ex).getMessage());
        ResponseEntity<Map<String, Object>> response = buildResponse(HttpStatus.SERVICE_UNAVAILABLE,
                "The service is temporarily unavailable. Please retry shortly.");
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(unavailableRetryAfterSeconds))
                .body(response.getBody());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getHeaderName() + " header is required");
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleMethodValidation(HandlerMethodValidationException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Idempotency-Key must not be blank and at most 255 characters");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getName() + "': " + ex.getValue());
    }

    // Client mistakes, not server faults: answered with the same body shape, logged below ERROR (DEBUG: scanners and typos would flood WARN).
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        log.debug("No resource for {} /{}", ex.getHttpMethod(), ex.getResourcePath());
        return buildResponse(HttpStatus.NOT_FOUND, "No resource found for " + ex.getHttpMethod() + " /" + ex.getResourcePath());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.debug("Method not supported: {}", ex.getMessage());
        ResponseEntity<Map<String, Object>> response = buildResponse(HttpStatus.METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported for this URL");
        var allowed = ex.getSupportedHttpMethods();
        if (allowed == null || allowed.isEmpty()) {
            return response;
        }
        return ResponseEntity.status(response.getStatusCode())
                .allow(allowed.toArray(new org.springframework.http.HttpMethod[0]))
                .body(response.getBody());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Unreadable request body: {}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Malformed or missing request body");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        // A request that was IN FLIGHT when the database went away fails with a wrapper Spring has no specific type for
        // (e.g. JpaSystemException around "FATAL: terminating connection due to administrator command"): look at the cause.
        if (causedByDatabaseUnavailable(ex)) {
            return handleDatabaseUnavailable(ex);
        }
        log.error("Unhandled exception", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred");
    }

    /**
     * True if somewhere in the cause chain is a JDBC "cannot reach / lost the database" error: a connection exception,
     * SQLSTATE class 08 (connection exception, e.g. 08001 cannot connect, 08006 connection failure) or 57P0x (the server
     * is shutting down or restarting, e.g. 57P01 admin shutdown). Constraint violations and the like do not match.
     */
    static boolean causedByDatabaseUnavailable(Throwable ex) {
        Throwable t = ex;
        for (int depth = 0; t != null && depth < 15; depth++, t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof SQLTransientConnectionException || t instanceof SQLNonTransientConnectionException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.startsWith("57P0"))) {
                    return true;
                }
                // The pool's closed-connection proxy ("Connection is closed", e.g. on the rollback after the server went away)
                // carries no SQLSTATE at all.
                String message = sql.getMessage();
                if (state == null && message != null && message.toLowerCase().contains("connection is closed")) {
                    return true;
                }
            }
        }
        return false;
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}