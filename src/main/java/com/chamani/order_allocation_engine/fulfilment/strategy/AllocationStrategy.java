package com.chamani.order_allocation_engine.fulfilment.strategy;

import com.chamani.order_allocation_engine.fulfilment.StrategyType;

import java.util.List;

/**
 * Decides WHICH batches an order is served from. A pure function: it gets the request and the candidate batches as
 * immutable values and returns a plan. No database, no Spring, no clock of its own (the shelf-life filter gets one injected),
 * so every strategy can be unit-tested with plain objects. Locking, applying the plan and persisting are FulfilmentService's job.
 *
 * <p>Plans are all-or-nothing (docs/adr/0001): either the whole quantity is planned, or the result is
 * {@link AllocationPlan#rejected(String)}. Implementations must give the same plan for the same input.
 */
public interface AllocationStrategy {

    StrategyType type();

    AllocationPlan plan(FulfilmentRequest request, List<BatchCandidate> candidates);
}
