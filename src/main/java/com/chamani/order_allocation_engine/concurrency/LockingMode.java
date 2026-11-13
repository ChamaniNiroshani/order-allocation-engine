package com.chamani.order_allocation_engine.concurrency;

/** How concurrent allocations for the same item are kept from overselling. */
public enum LockingMode {
    /** SELECT ... FOR UPDATE: requests queue on the row lock. */
    PESSIMISTIC,
    /** Plain read + @Version check, retried on conflict. */
    OPTIMISTIC,
    /** One conditional UPDATE ... WHERE quantity_available >= :q. */
    ATOMIC_UPDATE
}
