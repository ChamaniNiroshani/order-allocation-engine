package com.chamani.order_allocation_engine.fulfilment.strategy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.chamani.order_allocation_engine.fulfilment.strategy.StrategyFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** NEAREST as a pure function: plain objects in, a plan out. No Spring context, no database. */
class NearestWarehouseStrategyTest {

    private final NearestWarehouseStrategy strategy = new NearestWarehouseStrategy(filter(2));

    @Test
    void servesFromTheNearestWarehouseThatCanCoverTheWholeQuantity() {
        AllocationPlan plan = strategy.plan(orderToColombo(30), List.of(
                batch(1, WH_GALLE, 100, 30),
                batch(2, WH_KANDY, 100, 30),
                batch(3, WH_COLOMBO, 100, 30)));

        assertThat(plan.lines()).containsExactly(new AllocationPlan.Line(batchId(3), WH_COLOMBO, 30));
    }

    @Test
    void skipsANearerWarehouseThatCannotCoverTheWholeQuantity() {
        // Colombo is nearest but holds 40 in total, Kandy (next nearest) holds 100: all 60 come from Kandy, nothing from Colombo
        AllocationPlan plan = strategy.plan(orderToColombo(60), List.of(
                batch(1, WH_COLOMBO, 15, 30),
                batch(2, WH_COLOMBO, 25, 30),
                batch(3, WH_KANDY, 100, 30),
                batch(4, WH_GALLE, 100, 30)));

        assertThat(plan.lines()).containsExactly(new AllocationPlan.Line(batchId(3), WH_KANDY, 60));
    }

    @Test
    void insideTheWarehouse_batchesAreUsedEarliestExpiryFirst_andNeverExpiringLast() {
        AllocationPlan plan = strategy.plan(orderToColombo(50), List.of(
                batch(1, WH_COLOMBO, 20, null),
                batch(2, WH_COLOMBO, 20, 40),
                batch(3, WH_COLOMBO, 20, 10)));

        assertThat(plan.lines()).containsExactly(
                new AllocationPlan.Line(batchId(3), WH_COLOMBO, 20),
                new AllocationPlan.Line(batchId(2), WH_COLOMBO, 20),
                new AllocationPlan.Line(batchId(1), WH_COLOMBO, 10));
        assertThat(plan.totalQuantity()).isEqualTo(50);
    }

    @Test
    void sameExpiry_isBrokenByBatchId_soThePlanIsDeterministic() {
        AllocationPlan plan = strategy.plan(orderToColombo(5), List.of(
                batch(9, WH_COLOMBO, 5, 10),
                batch(4, WH_COLOMBO, 5, 10)));

        assertThat(plan.lines()).containsExactly(new AllocationPlan.Line(batchId(4), WH_COLOMBO, 5));
    }

    @Test
    void noSingleWarehouseCanCover_isRejected_evenIfAllWarehousesTogetherCould() {
        AllocationPlan plan = strategy.plan(orderToColombo(120), List.of(
                batch(1, WH_COLOMBO, 40, 30),
                batch(2, WH_KANDY, 50, 30),
                batch(3, WH_GALLE, 50, 30)));

        assertThat(plan.isRejected()).isTrue();
        assertThat(plan.lines()).isEmpty();
        assertThat(plan.rejectionReason()).contains("no single warehouse").contains("120").contains("MILK").contains("50");
    }

    @Test
    void expiredAndShortShelfLifeBatches_areNeverUsed() {
        // Colombo's stock is expired (-1) or has 1 day left (< the 2-day minimum), so Kandy serves the order
        AllocationPlan plan = strategy.plan(orderToColombo(10), List.of(
                batch(1, WH_COLOMBO, 100, -1),
                batch(2, WH_COLOMBO, 100, 1),
                batch(3, WH_KANDY, 100, 5)));

        assertThat(plan.lines()).containsExactly(new AllocationPlan.Line(batchId(3), WH_KANDY, 10));
    }

    @Test
    void onlyUnusableStock_isRejected() {
        AllocationPlan plan = strategy.plan(orderToColombo(1), List.of(batch(1, WH_COLOMBO, 100, -5)));

        assertThat(plan.isRejected()).isTrue();
    }

    @Test
    void noBatchesAtAll_isRejected() {
        assertThat(strategy.plan(orderToColombo(1), List.of()).isRejected()).isTrue();
    }

    @Test
    void sameDistance_isBrokenByWarehouseId() {
        // two warehouses at the same spot; the lower id wins every time
        BatchCandidate a = new BatchCandidate(batchId(1), WH_KANDY, 7.0, 80.0, 10, TODAY.plusDays(30));
        BatchCandidate b = new BatchCandidate(batchId(2), WH_COLOMBO, 7.0, 80.0, 10, TODAY.plusDays(30));

        AllocationPlan plan = strategy.plan(orderToColombo(5), List.of(a, b));

        assertThat(plan.lines()).containsExactly(new AllocationPlan.Line(batchId(2), WH_COLOMBO, 5));
    }

    @Test
    void doesNotChangeItsInput_andGivesTheSamePlanForTheSameInput() {
        List<BatchCandidate> input = List.of(batch(2, WH_COLOMBO, 20, 30), batch(1, WH_COLOMBO, 20, 10));

        assertThat(strategy.plan(orderToColombo(25), input)).isEqualTo(strategy.plan(orderToColombo(25), input));
        assertThat(input).extracting(BatchCandidate::batchId).containsExactly(batchId(2), batchId(1));
    }

    @Test
    void typeIsNearest() {
        assertThat(strategy.type()).isEqualTo(com.chamani.order_allocation_engine.fulfilment.StrategyType.NEAREST);
    }
}
