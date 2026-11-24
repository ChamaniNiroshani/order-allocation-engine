package com.chamani.order_allocation_engine.fulfilment.strategy;

import com.chamani.order_allocation_engine.fulfilment.StrategyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.chamani.order_allocation_engine.fulfilment.strategy.StrategyFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** FEFO as a pure function with a fixed Clock (today = 2026-06-01, minimum shelf life 2 days). */
class FefoStrategyTest {

    private final FefoStrategy strategy = new FefoStrategy(filter(2));

    @Test
    void usesTheEarliestExpiryFirst_acrossWarehouses_ignoringDistance() {
        // the order is delivered to Colombo, but the freshest-expiring stock is in Galle and Kandy
        AllocationPlan plan = strategy.plan(orderToColombo(60), List.of(
                batch(1, WH_COLOMBO, 100, 40),
                batch(2, WH_GALLE, 30, 5),
                batch(3, WH_KANDY, 50, 10)));

        assertThat(plan.lines()).containsExactly(
                new AllocationPlan.Line(batchId(2), WH_GALLE, 30),
                new AllocationPlan.Line(batchId(3), WH_KANDY, 30));
        assertThat(plan.totalQuantity()).isEqualTo(60);
    }

    @Test
    void batchesThatNeverExpire_comeLast() {
        AllocationPlan plan = strategy.plan(orderToColombo(25), List.of(
                batch(1, WH_COLOMBO, 20, null),
                batch(2, WH_KANDY, 20, 90)));

        assertThat(plan.lines()).containsExactly(
                new AllocationPlan.Line(batchId(2), WH_KANDY, 20),
                new AllocationPlan.Line(batchId(1), WH_COLOMBO, 5));
    }

    @Test
    void expiredBatches_areSkipped() {
        AllocationPlan plan = strategy.plan(orderToColombo(10), List.of(
                batch(1, WH_COLOMBO, 500, -1),
                batch(2, WH_KANDY, 10, 20)));

        assertThat(plan.lines()).containsExactly(new AllocationPlan.Line(batchId(2), WH_KANDY, 10));
    }

    @Test
    void batchesBelowTheMinimumShelfLife_areSkipped() {
        AllocationPlan plan = strategy.plan(orderToColombo(10), List.of(
                batch(1, WH_COLOMBO, 500, 1),     // 1 day left, minimum is 2
                batch(2, WH_COLOMBO, 10, 2)));    // exactly 2: allowed

        assertThat(plan.lines()).containsExactly(new AllocationPlan.Line(batchId(2), WH_COLOMBO, 10));
    }

    @Test
    void notEnoughFreshStock_isRejected_andNothingIsPartiallyPlanned() {
        AllocationPlan plan = strategy.plan(orderToColombo(100), List.of(
                batch(1, WH_COLOMBO, 40, 10),
                batch(2, WH_KANDY, 40, 10),
                batch(3, WH_GALLE, 500, -3)));    // plenty, but expired

        assertThat(plan.isRejected()).isTrue();
        assertThat(plan.lines()).isEmpty();
        assertThat(plan.rejectionReason()).contains("only 80 usable").contains("100 requested");
    }

    @Test
    void sameExpiry_isBrokenByBatchId() {
        AllocationPlan plan = strategy.plan(orderToColombo(5), List.of(
                batch(7, WH_KANDY, 5, 10),
                batch(3, WH_GALLE, 5, 10)));

        assertThat(plan.lines()).containsExactly(new AllocationPlan.Line(batchId(3), WH_GALLE, 5));
    }

    @Test
    void typeIsFefo() {
        assertThat(strategy.type()).isEqualTo(StrategyType.FEFO);
    }
}
