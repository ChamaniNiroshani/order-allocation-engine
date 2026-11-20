package com.chamani.order_allocation_engine.logging;

import com.chamani.order_allocation_engine.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.UUID;

/**
 * The key business events, one INFO line each, as structured key/values rather than a sentence with values pasted in:
 * a log system can filter on {@code event=order.rejected} or sum {@code quantity} without parsing text.
 * Key/values show up as fields in the JSON logs and as {@code key="value"} in the readable ones.
 * The action is {@code event.action}, the Elastic Common Schema name: a plain {@code event} string would clash with
 * ECS's {@code event} object. The order id is NOT a key/value: it is in the MDC for the duration of the call, and the JSON
 * formatter refuses (and drops the line) when a key/value and an MDC entry share a name.
 *
 * One line per order outcome, never per row or per SQL statement. Call these AFTER the transaction has committed,
 * so a line is never written for something that was rolled back.
 *
 * Deliberately not logged: the idempotency key and request bodies (client-supplied).
 */
public final class OrderEvents {

    private static final Logger log = LoggerFactory.getLogger(OrderEvents.class);

    /** The key of the event name; also what the tests filter on. */
    public static final String EVENT = "event.action";

    public static final String FLOW_DIRECT = "direct";
    public static final String FLOW_CHECKOUT = "checkout";

    private OrderEvents() {
    }

    /** Stock was allocated (direct) or the order was confirmed (checkout). */
    public static void allocated(String flow, Order order) {
        try (var ignored = OrderMdc.put(order.getOrderId())) {
            about(log.atInfo(), "order.allocated", flow, order).log("Order allocated");
        }
    }

    /** Not enough stock: a correct answer, recorded as a FAILED order. */
    public static void rejected(String flow, Order order) {
        try (var ignored = OrderMdc.put(order.getOrderId())) {
            about(log.atInfo(), "order.rejected", flow, order)
                    .addKeyValue("reason", order.getFailureReason())
                    .log("Order rejected");
        }
    }

    /** A checkout order was cancelled and its stock given back. {@code why} is declined or timeout. */
    public static void compensated(Order order, String why) {
        try (var ignored = OrderMdc.put(order.getOrderId())) {
            about(log.atInfo(), "order.compensated", FLOW_CHECKOUT, order)
                    .addKeyValue("why", why)
                    .log("Order compensated");
        }
    }

    /** The payment gateway was asked to give the money back. {@code why} is timeout or expired. */
    public static void refunded(Order order, String why) {
        try (var ignored = OrderMdc.put(order.getOrderId())) {
            about(log.atWarn(), "payment.refunded", FLOW_CHECKOUT, order)
                    .addKeyValue("why", why)
                    .log("Payment refunded");
        }
    }

    /** One line per cleanup run that did something, not one per order. */
    public static void reservationsCleanedUp(int expired, int confirmed) {
        log.atInfo()
                .addKeyValue(EVENT, "reservations.cleanup")
                .addKeyValue("expired", expired)
                .addKeyValue("confirmed", confirmed)
                .log("Checkout cleanup");
    }

    /** A retry with a known idempotency key found the order already stored. */
    public static void idempotentReplay(String flow, UUID orderId) {
        try (var ignored = OrderMdc.put(orderId)) {
            log.atInfo()
                    .addKeyValue(EVENT, "idempotency.conflict")
                    .addKeyValue("flow", flow)
                    .log("Concurrent request with the same idempotency key, returning the stored order");
        }
    }

    private static LoggingEventBuilder about(LoggingEventBuilder builder, String event, String flow, Order order) {
        return builder
                .addKeyValue(EVENT, event)
                .addKeyValue("flow", flow)
                .addKeyValue("itemId", order.getItemId())
                .addKeyValue("quantity", order.getQuantityRequested())
                .addKeyValue("status", order.getStatus());
    }
}
