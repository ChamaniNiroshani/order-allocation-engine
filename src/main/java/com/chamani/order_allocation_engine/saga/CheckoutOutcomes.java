package com.chamani.order_allocation_engine.saga;

import com.chamani.order_allocation_engine.model.Order;
import org.springframework.http.HttpStatus;

/**
 * The reasons the saga writes on an order, and the HTTP status each stored order maps to. The status
 * is derived from the stored order alone, so a replay of an idempotency key gets the same code as
 * the first call.
 */
public final class CheckoutOutcomes {

    public static final String REASON_INSUFFICIENT = "Insufficient inventory";
    public static final String REASON_DECLINED = "Payment declined";
    public static final String REASON_TIMEOUT = "Payment timed out";

    private CheckoutOutcomes() {}

    public static HttpStatus httpStatus(Order order) {
        return switch (order.getStatus()) {
            case CONFIRMED, ALLOCATED -> HttpStatus.CREATED;
            case FAILED, EXPIRED -> HttpStatus.CONFLICT;
            case CANCELLED -> {
                String reason = order.getFailureReason();
                yield reason != null && reason.startsWith(REASON_TIMEOUT) ? HttpStatus.BAD_GATEWAY : HttpStatus.PAYMENT_REQUIRED;
            }
            // Still in flight (a replay while another request is mid-saga): accepted, not finished.
            case PENDING, RESERVED, PAID -> HttpStatus.ACCEPTED;
            default -> HttpStatus.CONFLICT;
        };
    }
}
