package com.chamani.order_allocation_engine.fulfilment.strategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Template: every strategy runs the shared shelf-life filter first, then only decides the order in which batches are used. */
abstract class AbstractAllocationStrategy implements AllocationStrategy {

    /** Earliest expiry first, batches that never expire last; batch id breaks ties so the same input always gives the same plan. */
    static final Comparator<BatchCandidate> EARLIEST_EXPIRY_FIRST = Comparator
            .comparing(BatchCandidate::expiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(BatchCandidate::batchId);

    private final ShelfLifeFilter shelfLifeFilter;

    protected AbstractAllocationStrategy(ShelfLifeFilter shelfLifeFilter) {
        this.shelfLifeFilter = shelfLifeFilter;
    }

    @Override
    public final AllocationPlan plan(FulfilmentRequest request, List<BatchCandidate> candidates) {
        return doPlan(request, shelfLifeFilter.apply(candidates));
    }

    /** @param usable candidates that passed the shelf-life filter, in no particular order */
    protected abstract AllocationPlan doPlan(FulfilmentRequest request, List<BatchCandidate> usable);

    /** Takes batches in the given order until {@code quantity} is covered. Null if they do not hold enough. */
    static List<AllocationPlan.Line> take(List<BatchCandidate> ordered, int quantity) {
        List<AllocationPlan.Line> lines = new ArrayList<>();
        int remaining = quantity;
        for (BatchCandidate batch : ordered) {
            if (remaining == 0) {
                break;
            }
            int taken = Math.min(remaining, batch.quantityAvailable());
            lines.add(new AllocationPlan.Line(batch.batchId(), batch.warehouseId(), taken));
            remaining -= taken;
        }
        return remaining == 0 ? lines : null;
    }

    static int total(List<BatchCandidate> batches) {
        return batches.stream().mapToInt(BatchCandidate::quantityAvailable).sum();
    }
}
