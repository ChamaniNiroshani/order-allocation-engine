package com.chamani.order_allocation_engine;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "allocation.locking-mode=OPTIMISTIC")
class OptimisticAllocationConcurrencyTest extends AbstractAllocationConcurrencyTest {

    /** Optimistic gives up after a bounded number of retries by design (503 + Retry-After): see the base class. */
    @Override
    protected boolean contentionIsValidOutcome() {
        return true;
    }
}
