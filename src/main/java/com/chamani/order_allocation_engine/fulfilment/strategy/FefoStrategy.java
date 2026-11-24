package com.chamani.order_allocation_engine.fulfilment.strategy;

import com.chamani.order_allocation_engine.fulfilment.StrategyType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * First-Expired-First-Out: take batches across ALL warehouses, earliest expiry first (batches that never expire last).
 * For perishable stock freshness beats distance, so an order may be split over several warehouses. Distance is ignored.
 */
@Component
public class FefoStrategy extends AbstractAllocationStrategy {

    public FefoStrategy(ShelfLifeFilter shelfLifeFilter) {
        super(shelfLifeFilter);
    }

    @Override
    public StrategyType type() {
        return StrategyType.FEFO;
    }

    @Override
    protected AllocationPlan doPlan(FulfilmentRequest request, List<BatchCandidate> usable) {
        List<AllocationPlan.Line> lines = take(usable.stream().sorted(EARLIEST_EXPIRY_FIRST).toList(), request.quantity());
        if (lines == null) {
            return AllocationPlan.rejected("Insufficient stock: only " + total(usable) + " usable units of " + request.sku()
                    + " across all warehouses, " + request.quantity() + " requested");
        }
        return AllocationPlan.of(lines);
    }
}
