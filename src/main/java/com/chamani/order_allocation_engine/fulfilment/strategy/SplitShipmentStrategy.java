package com.chamani.order_allocation_engine.fulfilment.strategy;

import com.chamani.order_allocation_engine.fulfilment.StrategyType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Warehouses are used nearest-first, and an order is split over as few of them as the greedy walk needs: if the nearest
 * warehouse alone can cover the quantity the result is a single shipment, otherwise the rest comes from the next nearest, and so
 * on. Inside each warehouse batches go earliest-expiry first. Still all-or-nothing: if all warehouses together cannot cover it, rejected.
 *
 * <p>Difference to NEAREST: NEAREST never splits and may skip a close warehouse that is too small to go to a far one that
 * is big enough; SPLIT always starts at the closest warehouse and tops up from the following ones.
 */
@Component
public class SplitShipmentStrategy extends AbstractAllocationStrategy {

    public SplitShipmentStrategy(ShelfLifeFilter shelfLifeFilter) {
        super(shelfLifeFilter);
    }

    @Override
    public StrategyType type() {
        return StrategyType.SPLIT;
    }

    @Override
    protected AllocationPlan doPlan(FulfilmentRequest request, List<BatchCandidate> usable) {
        Map<UUID, List<BatchCandidate>> byWarehouse = usable.stream().collect(Collectors.groupingBy(BatchCandidate::warehouseId));

        List<BatchCandidate> ordered = byWarehouse.entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, List<BatchCandidate>>>comparingDouble(
                                e -> NearestWarehouseStrategy.distanceKm(request, e.getValue().get(0)))
                        .thenComparing(Map.Entry::getKey))
                .flatMap(e -> e.getValue().stream().sorted(EARLIEST_EXPIRY_FIRST))
                .toList();

        List<AllocationPlan.Line> lines = take(ordered, request.quantity());
        if (lines == null) {
            return AllocationPlan.rejected("Insufficient stock: only " + total(usable) + " usable units of " + request.sku()
                    + " across all warehouses, " + request.quantity() + " requested");
        }
        return AllocationPlan.of(lines);
    }
}
