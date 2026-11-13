package com.chamani.order_allocation_engine;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "allocation.locking-mode=PESSIMISTIC")
class PessimisticAllocationConcurrencyTest extends AbstractAllocationConcurrencyTest {
}
