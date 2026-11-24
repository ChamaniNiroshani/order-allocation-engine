package com.chamani.order_allocation_engine.fulfilment.strategy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.chamani.order_allocation_engine.fulfilment.strategy.StrategyFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShelfLifeFilterTest {

    @Test
    void dropsExpiredStockAndStockBelowTheMinimumShelfLife() {
        List<BatchCandidate> kept = filter(2).apply(List.of(
                batch(1, WH_COLOMBO, 10, -1),     // expired yesterday
                batch(2, WH_COLOMBO, 10, 0),      // expires today
                batch(3, WH_COLOMBO, 10, 1),      // 1 day left: below the minimum of 2
                batch(4, WH_COLOMBO, 10, 2),      // exactly the minimum: kept
                batch(5, WH_COLOMBO, 10, 90),
                batch(6, WH_COLOMBO, 10, null)));  // never expires

        assertThat(kept).extracting(BatchCandidate::batchId).containsExactly(batchId(4), batchId(5), batchId(6));
    }

    @Test
    void dropsEmptyBatches() {
        assertThat(filter(2).apply(List.of(batch(1, WH_COLOMBO, 0, 30)))).isEmpty();
    }

    @Test
    void minimumOfZero_stillNeverShipsExpiredStock() {
        List<BatchCandidate> kept = filter(0).apply(List.of(batch(1, WH_COLOMBO, 10, -1), batch(2, WH_COLOMBO, 10, 0)));

        assertThat(kept).extracting(BatchCandidate::batchId).containsExactly(batchId(2));
    }

    @Test
    void negativeMinimum_isRefusedAtStartup() {
        assertThatThrownBy(() -> new ShelfLifeFilter(CLOCK, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
