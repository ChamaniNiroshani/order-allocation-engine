package com.chamani.order_allocation_engine.fulfilment;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

/** A lot of one SKU in one warehouse. expiryDate is null for stock that never expires. */
@Entity
@Table(name = "inventory_batch")
public class InventoryBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "qty_available", nullable = false)
    private Integer qtyAvailable;

    @Column(name = "qty_reserved", nullable = false)
    private Integer qtyReserved = 0;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Version
    private Long version;

    protected InventoryBatch() {}

    public InventoryBatch(String sku, UUID warehouseId, Integer qtyAvailable, LocalDate expiryDate) {
        this.sku = sku;
        this.warehouseId = warehouseId;
        this.qtyAvailable = qtyAvailable;
        this.expiryDate = expiryDate;
    }

    public UUID getId() { return id; }
    public String getSku() { return sku; }
    public UUID getWarehouseId() { return warehouseId; }
    public Integer getQtyAvailable() { return qtyAvailable; }
    public Integer getQtyReserved() { return qtyReserved; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public Long getVersion() { return version; }
}
