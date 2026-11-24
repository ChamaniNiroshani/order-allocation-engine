package com.chamani.order_allocation_engine.fulfilment.strategy;

import com.chamani.order_allocation_engine.fulfilment.StrategyType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One shipment from the nearest warehouse (Haversine distance to the delivery location) that can cover the WHOLE quantity.
 * Inside that warehouse batches are used earliest-expiry first. If no single warehouse can, the order is rejected.
 */
@Component
public class NearestWarehouseStrategy extends AbstractAllocationStrategy {

    public NearestWarehouseStrategy(ShelfLifeFilter shelfLifeFilter) {
        super(shelfLifeFilter);
    }

    @Override
    public StrategyType type() {
        return StrategyType.NEAREST;
    }

    @Override
    protected AllocationPlan doPlan(FulfilmentRequest request, List<BatchCandidate> usable) {
        Map<UUID, List<BatchCandidate>> byWarehouse = usable.stream()
                .collect(Collectors.groupingBy(BatchCandidate::warehouseId));

        UUID nearest = byWarehouse.entrySet().stream()
                .filter(e -> total(e.getValue()) >= request.quantity())
                .min(Comparator.<Map.Entry<UUID, List<BatchCandidate>>>comparingDouble(e -> distanceKm(request, e.getValue().get(0)))
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(null);

        if (nearest == null) {
            int biggest = byWarehouse.values().stream().mapToInt(AbstractAllocationStrategy::total).max().orElse(0);
            return AllocationPlan.rejected("Insufficient stock: no single warehouse can supply " + request.quantity()
                    + " of " + request.sku() + " (most in one warehouse: " + biggest + ")");
        }
        List<BatchCandidate> ordered = byWarehouse.get(nearest).stream().sorted(EARLIEST_EXPIRY_FIRST).toList();
        return AllocationPlan.of(take(ordered, request.quantity()));
    }

    static double distanceKm(FulfilmentRequest request, BatchCandidate batch) {
        return Haversine.distanceKm(request.deliveryLat(), request.deliveryLon(), batch.warehouseLat(), batch.warehouseLon());
    }
}
