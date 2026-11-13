package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.service.OrderAllocationFacade;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestPropertySource(properties = "allocation.locking-mode=ATOMIC_UPDATE")
class AtomicUpdateAllocationConcurrencyTest extends AbstractAllocationConcurrencyTest {

    @Autowired
    private OrderAllocationFacade facade;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    @Test
    void successfulDecrement_bumpsVersion_rejectionDoesNot() {
        UUID itemId = inventoryItemRepository.save(new InventoryItem(10)).getItemId();

        facade.allocate(itemId, 10, "junit-atomic-" + UUID.randomUUID());
        assertEquals(1L, inventoryItemRepository.findById(itemId).orElseThrow().getVersion().longValue());

        facade.allocate(itemId, 1, "junit-atomic-" + UUID.randomUUID());   // out of stock: rejected
        assertEquals(1L, inventoryItemRepository.findById(itemId).orElseThrow().getVersion().longValue());
    }
}
