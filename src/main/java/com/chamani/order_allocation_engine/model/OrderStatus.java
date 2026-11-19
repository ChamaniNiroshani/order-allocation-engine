package com.chamani.order_allocation_engine.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Order lifecycle. The direct flow (POST /api/orders) uses PENDING -> ALLOCATED | FAILED.
 * The checkout saga uses PENDING -> RESERVED -> PAID -> CONFIRMED, with CANCELLED / EXPIRED as the
 * compensated endings. The status IS the saga state, so every change goes through canTransitionTo.
 */
public enum OrderStatus {
    PENDING, ALLOCATED, PARTIALLY_ALLOCATED, FAILED, ROLLED_BACK,
    RESERVED, PAID, CONFIRMED, CANCELLED, EXPIRED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        for (OrderStatus s : values()) {
            ALLOWED.put(s, EnumSet.noneOf(OrderStatus.class));
        }
        ALLOWED.get(PENDING).addAll(EnumSet.of(RESERVED, FAILED, ALLOCATED));
        ALLOWED.get(RESERVED).addAll(EnumSet.of(PAID, CANCELLED, EXPIRED));
        ALLOWED.get(PAID).addAll(EnumSet.of(CONFIRMED, CANCELLED)); // PAID -> CANCELLED = refund case
    }

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
