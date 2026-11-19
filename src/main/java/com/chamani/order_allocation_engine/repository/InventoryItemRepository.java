package com.chamani.order_allocation_engine.repository;

import com.chamani.order_allocation_engine.model.InventoryItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    // SELECT ... FOR UPDATE: concurrent allocations for the same item queue up
    // on the row lock instead of colliding on the @Version check.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InventoryItem i WHERE i.itemId = :itemId")
    Optional<InventoryItem> findByIdForUpdate(@Param("itemId") UUID itemId);

    // One statement does the check and the decrement, so there is no read-then-write gap.
    // Returns 1 if stock was taken, 0 if the item is unknown OR has fewer than :quantity.
    // A bulk update bypasses the persistence context: flush pending changes first, and clear
    // it afterwards so no stale InventoryItem is served from the first-level cache.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE inventory_item
               SET quantity_available = quantity_available - :quantity,
                   version = version + 1
             WHERE item_id = :itemId AND quantity_available >= :quantity
            """, nativeQuery = true)
    int decrementIfAvailable(@Param("itemId") UUID itemId, @Param("quantity") int quantity);

    // ---- checkout saga stock moves: one conditional UPDATE each, no read before write ----------------
    // Each bumps version and returns the rows changed: 1 = done, 0 = the condition did not hold.
    // flushAutomatically writes pending changes (e.g. the order's new state) first. The persistence
    // context is NOT cleared: the saga steps never load InventoryItem entities, but they do hold
    // the locked Order, which clearing would detach.

    /** available -= q, reserved += q, only if enough is available. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE inventory_item
               SET quantity_available = quantity_available - :quantity,
                   quantity_reserved = quantity_reserved + :quantity,
                   version = version + 1
             WHERE item_id = :itemId AND quantity_available >= :quantity
            """, nativeQuery = true)
    int reserveIfAvailable(@Param("itemId") UUID itemId, @Param("quantity") int quantity);

    /** available += q, reserved -= q, only if that much is reserved. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE inventory_item
               SET quantity_available = quantity_available + :quantity,
                   quantity_reserved = quantity_reserved - :quantity,
                   version = version + 1
             WHERE item_id = :itemId AND quantity_reserved >= :quantity
            """, nativeQuery = true)
    int releaseReserved(@Param("itemId") UUID itemId, @Param("quantity") int quantity);

    /** reserved -= q (the units leave the warehouse), only if that much is reserved. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE inventory_item
               SET quantity_reserved = quantity_reserved - :quantity,
                   version = version + 1
             WHERE item_id = :itemId AND quantity_reserved >= :quantity
            """, nativeQuery = true)
    int confirmReserved(@Param("itemId") UUID itemId, @Param("quantity") int quantity);
}
