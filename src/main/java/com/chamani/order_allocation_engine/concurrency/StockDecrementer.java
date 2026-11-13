package com.chamani.order_allocation_engine.concurrency;

import java.util.UUID;

/**
 * Takes stock out of an inventory item without overselling. Implementations run inside the
 * caller's transaction (AllocationService.allocate), so the decrement commits or rolls back
 * together with the order.
 */
public interface StockDecrementer {

    /** The locking mode this implementation provides. */
    LockingMode mode();

    /**
     * @return DECREMENTED, or INSUFFICIENT when fewer than {@code quantity} are available
     * @throws IllegalArgumentException if the item does not exist (mapped to 404)
     */
    DecrementResult decrement(UUID itemId, int quantity);
}
