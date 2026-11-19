package com.chamani.order_allocation_engine;

import com.chamani.order_allocation_engine.model.InventoryItem;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.service.StockReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** reserve / release / confirm against a real Postgres: each is one conditional UPDATE. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockReservationServiceTest {

    @Autowired
    private StockReservationService stock;

    @Autowired
    private InventoryItemRepository inventoryItemRepository;

    private UUID newItem(int available) {
        return inventoryItemRepository.save(new InventoryItem(available)).getItemId();
    }

    private InventoryItem item(UUID id) {
        return inventoryItemRepository.findById(id).orElseThrow();
    }

    @Test
    void reserve_movesStockFromAvailableToReserved_andBumpsVersion() {
        UUID id = newItem(10);
        long before = item(id).getVersion();

        assertTrue(stock.reserve(id, 4));

        InventoryItem after = item(id);
        assertEquals(6, after.getQuantityAvailable());
        assertEquals(4, after.getQuantityReserved());
        assertEquals(before + 1, after.getVersion());
    }

    @Test
    void reserve_exactlyTheAvailableQuantity_works() {
        UUID id = newItem(5);

        assertTrue(stock.reserve(id, 5));

        assertEquals(0, item(id).getQuantityAvailable());
        assertEquals(5, item(id).getQuantityReserved());
    }

    @Test
    void reserve_insufficientStock_returnsFalse_andChangesNothing() {
        UUID id = newItem(3);
        long before = item(id).getVersion();

        assertFalse(stock.reserve(id, 4));

        InventoryItem after = item(id);
        assertEquals(3, after.getQuantityAvailable());
        assertEquals(0, after.getQuantityReserved());
        assertEquals(before, after.getVersion(), "a failed reserve must not bump the version");
    }

    @Test
    void reserve_unknownItem_isNotFound() {
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(UUID.randomUUID(), 1));
    }

    @Test
    void release_putsReservedStockBack_andBumpsVersion() {
        UUID id = newItem(10);
        stock.reserve(id, 4);
        long before = item(id).getVersion();

        stock.release(id, 4);

        InventoryItem after = item(id);
        assertEquals(10, after.getQuantityAvailable());
        assertEquals(0, after.getQuantityReserved());
        assertEquals(before + 1, after.getVersion());
    }

    @Test
    void release_moreThanReserved_throws_andChangesNothing() {
        UUID id = newItem(10);
        stock.reserve(id, 2);

        assertThrows(IllegalStateException.class, () -> stock.release(id, 3));

        assertEquals(8, item(id).getQuantityAvailable());
        assertEquals(2, item(id).getQuantityReserved());
    }

    @Test
    void confirm_consumesReservedStock_andLeavesAvailableAlone() {
        UUID id = newItem(10);
        stock.reserve(id, 4);
        long before = item(id).getVersion();

        stock.confirm(id, 4);

        InventoryItem after = item(id);
        assertEquals(6, after.getQuantityAvailable());
        assertEquals(0, after.getQuantityReserved());
        assertEquals(before + 1, after.getVersion());
    }

    @Test
    void confirm_moreThanReserved_throws_andChangesNothing() {
        UUID id = newItem(10);
        stock.reserve(id, 2);

        assertThrows(IllegalStateException.class, () -> stock.confirm(id, 3));

        assertEquals(8, item(id).getQuantityAvailable());
        assertEquals(2, item(id).getQuantityReserved());
    }

    @Test
    void reserveThenRelease_isSymmetric_forManyRounds() {
        UUID id = newItem(10);

        for (int i = 0; i < 25; i++) {
            assertTrue(stock.reserve(id, 7));
            stock.release(id, 7);
        }

        assertEquals(10, item(id).getQuantityAvailable());
        assertEquals(0, item(id).getQuantityReserved());
    }
}
