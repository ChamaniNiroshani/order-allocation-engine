package com.chamani.order_allocation_engine.logging;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Puts an order id on every log line written inside a try-with-resources block, then takes it off again:
 * <pre>
 * try (var ignored = OrderMdc.put(order.getOrderId())) {
 *     ... everything logged here carries orderId ...
 * }
 * </pre>
 * Restores whatever was there before, so scopes nest, and a thread that is reused never keeps a stale id.
 */
public final class OrderMdc {

    public static final String ORDER_ID = "orderId";

    /** A scope that ends without a checked exception, so try-with-resources needs no catch. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private OrderMdc() {
    }

    public static Scope put(UUID orderId) {
        if (orderId == null) {
            return () -> { };                 // logging context must never be able to fail a request
        }
        String previous = MDC.get(ORDER_ID);
        MDC.put(ORDER_ID, orderId.toString());
        return () -> {
            if (previous == null) {
                MDC.remove(ORDER_ID);
            } else {
                MDC.put(ORDER_ID, previous);
            }
        };
    }
}
