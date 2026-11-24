package com.chamani.order_allocation_engine.fulfilment.strategy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/** Shared test data for the strategy unit tests: three Sri Lankan warehouses and a fixed "today". No Spring, no database. */
final class StrategyFixtures {

    static final LocalDate TODAY = LocalDate.of(2026, 6, 1);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-01T10:00:00Z"), ZoneOffset.UTC);

    // the order is delivered to Colombo; Kandy is 94 km away, Galle 105 km
    static final double[] COLOMBO = {6.9271, 79.8612};
    static final double[] KANDY = {7.2906, 80.6337};
    static final double[] GALLE = {6.0535, 80.2210};

    static final UUID WH_COLOMBO = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    static final UUID WH_KANDY = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    static final UUID WH_GALLE = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    private StrategyFixtures() {}

    static ShelfLifeFilter filter(int minDays) {
        return new ShelfLifeFilter(CLOCK, minDays);
    }

    /** Batch ids sort in the order of {@code n}, so tests can predict tie-breaks. */
    static UUID batchId(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }

    static BatchCandidate batch(int n, UUID warehouse, int qty, Integer expiresInDays) {
        double[] at = warehouse.equals(WH_COLOMBO) ? COLOMBO : warehouse.equals(WH_KANDY) ? KANDY : GALLE;
        return new BatchCandidate(batchId(n), warehouse, at[0], at[1], qty,
                expiresInDays == null ? null : TODAY.plusDays(expiresInDays));
    }

    static FulfilmentRequest orderToColombo(int quantity) {
        return new FulfilmentRequest("MILK", quantity, COLOMBO[0], COLOMBO[1]);
    }
}
