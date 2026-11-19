package com.chamani.order_allocation_engine.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an id that appears on every log line it causes and in the response, so one request can be
 * followed through the logs.
 *
 * A caller may supply its own X-Request-Id (so a gateway's id continues through this service), but only if it is
 * at most 64 characters of letters, digits and dashes. Anything else is replaced: the value goes into log lines, and a
 * caller must not be able to forge a log entry with a newline or flood the log with a huge header.
 *
 * Runs first, and always clears what it put in the MDC: server threads are reused, so a value left behind would
 * show up on the next, unrelated request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    static final int MAX_LENGTH = 64;

    private static final Pattern ACCEPTED = Pattern.compile("[A-Za-z0-9-]{1," + MAX_LENGTH + "}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = accept(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);          // set before the chain runs, so error responses carry it too
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            MDC.remove(OrderMdc.ORDER_ID);
        }
    }

    /** The caller's id if it is safe to log, otherwise a fresh one. */
    static String accept(String supplied) {
        if (supplied != null && ACCEPTED.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
