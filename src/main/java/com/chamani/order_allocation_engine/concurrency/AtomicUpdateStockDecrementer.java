package com.chamani.order_allocation_engine.concurrency;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * A single conditional UPDATE. Postgres locks the row for the statement, and a concurrent
 * writer re-checks the WHERE clause once the lock is free, so stock can never go negative and
 * there are no version conflicts to retry.
 */
@Component
public class AtomicUpdateStockDecrementer implements StockDecrementer {

    private final InventoryItemRepository inventoryItemRepository;

    public AtomicUpdateStockDecrementer(InventoryItemRepository inventoryItemRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
    }

    @Override
    public LockingMode mode() {
        return LockingMode.ATOMIC_UPDATE;
    }

    @Override
    public DecrementResult decrement(UUID itemId, int quantity) {
        int updated = inventoryItemRepository.decrementIfAvailable(itemId, quantity);

        // The persistence context was cleared by the bulk update, so this reads the fresh row
        // (and sees our own uncommitted update under READ COMMITTED).
        InventoryItem item = inventoryItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown itemId: " + itemId));

        return updated == 1
                ? DecrementResult.decremented(item.getQuantityAvailable())
                : DecrementResult.insufficient(item.getQuantityAvailable());
    }
}
