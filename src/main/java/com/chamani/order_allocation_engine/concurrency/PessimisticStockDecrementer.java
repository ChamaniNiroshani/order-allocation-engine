package com.chamani.order_allocation_engine.concurrency;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** SELECT ... FOR UPDATE, then check and decrement while holding the row lock. */
@Component
public class PessimisticStockDecrementer implements StockDecrementer {

    private final InventoryItemRepository inventoryItemRepository;

    public PessimisticStockDecrementer(InventoryItemRepository inventoryItemRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
    }

    @Override
    public LockingMode mode() {
        return LockingMode.PESSIMISTIC;
    }

    @Override
    public DecrementResult decrement(UUID itemId, int quantity) {
        // Concurrent allocations for the same item queue up on the row lock instead of
        // colliding on the @Version check.
        InventoryItem item = inventoryItemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown itemId: " + itemId));

        if (item.getQuantityAvailable() < quantity) {
            return DecrementResult.insufficient(item.getQuantityAvailable());
        }

        item.setQuantityAvailable(item.getQuantityAvailable() - quantity);
        inventoryItemRepository.save(item);
        return DecrementResult.decremented(item.getQuantityAvailable());
    }
}
