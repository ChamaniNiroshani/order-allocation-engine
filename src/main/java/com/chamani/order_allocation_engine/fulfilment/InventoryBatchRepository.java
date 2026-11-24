package com.chamani.order_allocation_engine.fulfilment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface InventoryBatchRepository extends JpaRepository<InventoryBatch, UUID> {

    /**
     * Every batch of the SKU, row-locked (SELECT ... WHERE sku = :sku ORDER BY id FOR UPDATE).
     * ORDER BY id is what makes this safe: all transactions take their row locks in ascending batch-id order, so two of
     * them can wait for each other's locks but never in a cycle (docs/adr/0005-lock-ordering.md).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBatch b where b.sku = :sku order by b.id")
    List<InventoryBatch> lockBySkuOrderById(@Param("sku") String sku);
}
