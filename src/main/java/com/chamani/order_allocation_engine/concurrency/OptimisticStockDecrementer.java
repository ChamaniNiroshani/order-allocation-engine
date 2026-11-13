package com.chamani.order_allocation_engine.concurrency;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Plain read, modify, write; the @Version column makes a concurrent writer lose. The conflict
 * surfaces as ObjectOptimisticLockingFailureException when the surrounding transaction flushes
 * at commit, which is why the retry lives outside it (OptimisticRetryExecutor).
 */
@Component
public class OptimisticStockDecrementer implements StockDecrementer {

    private final InventoryItemRepository inventoryItemRepository;

    public OptimisticStockDecrementer(InventoryItemRepository inventoryItemRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
    }

    @Override
    public LockingMode mode() {
        return LockingMode.OPTIMISTIC;
    }

    @Override
    public DecrementResult decrement(UUID itemId, int quantity) {
        InventoryItem item = inventoryItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown itemId: " + itemId));

        if (item.getQuantityAvailable() < quantity) {
            return DecrementResult.insufficient(item.getQuantityAvailable());
        }

        item.setQuantityAvailable(item.getQuantityAvailable() - quantity);
        inventoryItemRepository.save(item);
        return DecrementResult.decremented(item.getQuantityAvailable());
    }
}
