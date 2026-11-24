package com.chamani.order_allocation_engine.fulfilment.strategy;

import com.chamani.order_allocation_engine.fulfilment.StrategyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.chamani.order_allocation_engine.fulfilment.strategy.StrategyFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/** SPLIT as a pure function. The order is delivered to Colombo; Kandy is 94 km away, Galle 105 km. */
class SplitShipmentStrategyTest {

    private final SplitShipmentStrategy strategy = new SplitShipmentStrategy(filter(2));

    @Test
    void nearestWarehouseCanCover_singleShipmentFromIt() {
        AllocationPlan plan = strategy.plan(orderToColombo(30), List.of(
                batch(1, WH_KANDY, 100, 30),
                batch(2, WH_COLOMBO, 40, 30)));

        assertThat(plan.lines()).containsExactly(new AllocationPlan.Line(batchId(2), WH_COLOMBO, 30));
    }

    @Test
    void nearestCannotCover_takesAllItHas_thenTopsUpFromTheNextNearest() {
        AllocationPlan plan = strategy.plan(orderToColombo(60), List.of(
                batch(1, WH_GALLE, 100, 30),
                batch(2, WH_KANDY, 100, 30),
                batch(3, WH_COLOMBO, 15, 30),
                batch(4, WH_COLOMBO, 25, 20)));

        // Colombo first (earliest expiry inside it first), then Kandy (94 km) before Galle (105 km)
        assertThat(plan.lines()).containsExactly(
                new AllocationPlan.Line(batchId(4), WH_COLOMBO, 25),
                new AllocationPlan.Line(batchId(3), WH_COLOMBO, 15),
                new AllocationPlan.Line(batchId(2), WH_KANDY, 20));
    }

    @Test
    void usesAsManyWarehousesAsNeeded() {
        AllocationPlan plan = strategy.plan(orderToColombo(90), List.of(
                batch(1, WH_COLOMBO, 30, 30),
                batch(2, WH_KANDY, 30, 30),
                batch(3, WH_GALLE, 30, 30)));

        assertThat(plan.lines()).extracting(AllocationPlan.Line::warehouseId).containsExactly(WH_COLOMBO, WH_KANDY, WH_GALLE);
        assertThat(plan.totalQuantity()).isEqualTo(90);
    }

    @Test
    void allTogetherStillNotEnough_isRejected() {
        AllocationPlan plan = strategy.plan(orderToColombo(100), List.of(
                batch(1, WH_COLOMBO, 30, 30),
                batch(2, WH_KANDY, 30, 30)));

        assertThat(plan.isRejected()).isTrue();
        assertThat(plan.rejectionReason()).contains("only 60 usable").contains("100 requested");
    }

    @Test
    void expiredAndShortShelfLifeStock_doesNotCountTowardsTheTotal() {
        AllocationPlan plan = strategy.plan(orderToColombo(50), List.of(
                batch(1, WH_COLOMBO, 30, 30),
                batch(2, WH_KANDY, 100, -1),
                batch(3, WH_GALLE, 100, 1)));

        assertThat(plan.isRejected()).isTrue();
        assertThat(plan.rejectionReason()).contains("only 30 usable");
    }

    @Test
    void typeIsSplit() {
        assertThat(strategy.type()).isEqualTo(StrategyType.SPLIT);
    }
}
