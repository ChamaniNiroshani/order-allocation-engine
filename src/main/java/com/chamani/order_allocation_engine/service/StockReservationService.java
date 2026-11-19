package com.chamani.order_allocation_engine.service;

import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Stock moves for the checkout saga, each a single atomic conditional UPDATE. They join the caller's
 * transaction, so a stock move and the order status change that goes with it commit or roll back together.
 */
@Service
public class StockReservationService {

    private final InventoryItemRepository inventoryItemRepository;

    public StockReservationService(InventoryItemRepository inventoryItemRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
    }

    /**
     * available -= q, reserved += q.
     *
     * @return true if reserved, false if there is not enough available stock
     * @throws IllegalArgumentException if the item does not exist (mapped to 404)
     */
    @Transactional
    public boolean reserve(UUID itemId, int quantity) {
        if (inventoryItemRepository.reserveIfAvailable(itemId, quantity) == 1) {
            return true;
        }
        // 0 rows: unknown item or not enough stock. Only now is it worth a second query.
        if (!inventoryItemRepository.existsById(itemId)) {
            throw new IllegalArgumentException("Unknown itemId: " + itemId);
        }
        return false;
    }

    /**
     * available += q, reserved -= q. Only called on a RESERVED -> CANCELLED/EXPIRED transition, so the
     * reserved quantity must be there; if it is not, the data is inconsistent and the transaction rolls back.
     */
    @Transactional
    public void release(UUID itemId, int quantity) {
        if (inventoryItemRepository.releaseReserved(itemId, quantity) != 1) {
            throw new IllegalStateException("Cannot release " + quantity + " of item " + itemId + ": not reserved");
        }
    }

    /** reserved -= q. Only called on PAID -> CONFIRMED; same rollback rule as {@link #release}. */
    @Transactional
    public void confirm(UUID itemId, int quantity) {
        if (inventoryItemRepository.confirmReserved(itemId, quantity) != 1) {
            throw new IllegalStateException("Cannot confirm " + quantity + " of item " + itemId + ": not reserved");
        }
    }
}
