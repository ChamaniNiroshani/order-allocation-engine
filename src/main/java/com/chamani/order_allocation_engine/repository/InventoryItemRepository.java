package com.chamani.order_allocation_engine.repository;

import com.chamani.order_allocation_engine.model.InventoryItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
