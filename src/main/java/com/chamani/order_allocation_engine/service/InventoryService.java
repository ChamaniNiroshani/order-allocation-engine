package com.chamani.order_allocation_engine.service;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class InventoryService {

    private final InventoryItemRepository inventoryItemRepository;

    public InventoryService(InventoryItemRepository inventoryItemRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
    }

    @Transactional
    public InventoryItem create(int quantityAvailable) {
        return inventoryItemRepository.save(new InventoryItem(quantityAvailable));
    }

    @Transactional(readOnly = true)
    public InventoryItem get(UUID itemId) {
        // IllegalArgumentException is mapped to 404 by GlobalExceptionHandler (same as allocation).
        return inventoryItemRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown itemId: " + itemId));
    }
}
