package com.chamani.order_allocation_engine.fulfilment.strategy;

import java.util.List;
import java.util.UUID;

/** Either the lines that fully cover the order, or a rejection with its reason. */
public record AllocationPlan(List<Line> lines, String rejectionReason) {

    /** Take {@code quantity} units from one batch. */
    public record Line(UUID batchId, UUID warehouseId, int quantity) {
        public Line {
            if (quantity < 1) {
                throw new IllegalArgumentException("a plan line must take at least 1 unit");
            }
        }
    }

    public AllocationPlan {
        lines = List.copyOf(lines);
        if (rejectionReason == null && lines.isEmpty()) {
            throw new IllegalArgumentException("a plan without lines must be rejected with a reason");
        }
        if (rejectionReason != null && !lines.isEmpty()) {
            throw new IllegalArgumentException("a rejected plan has no lines");
        }
    }

    public static AllocationPlan of(List<Line> lines) {
        return new AllocationPlan(lines, null);
    }

    public static AllocationPlan rejected(String reason) {
        return new AllocationPlan(List.of(), reason);
    }

    public boolean isRejected() {
        return rejectionReason != null;
    }

    public int totalQuantity() {
        return lines.stream().mapToInt(Line::quantity).sum();
    }
}
