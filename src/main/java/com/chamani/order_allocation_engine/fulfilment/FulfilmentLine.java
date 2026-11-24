package com.chamani.order_allocation_engine.fulfilment;

import jakarta.persistence.*;
import java.util.UUID;

/** Part of a fulfilment order served from one batch. */
@Entity
@Table(name = "fulfilment_line")
public class FulfilmentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "fulfilment_order_id", nullable = false)
    private UUID fulfilmentOrderId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(nullable = false)
    private Integer quantity;

    protected FulfilmentLine() {}

    public FulfilmentLine(UUID fulfilmentOrderId, UUID batchId, UUID warehouseId, Integer quantity) {
        this.fulfilmentOrderId = fulfilmentOrderId;
        this.batchId = batchId;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
    }

    public UUID getId() { return id; }
    public UUID getFulfilmentOrderId() { return fulfilmentOrderId; }
    public UUID getBatchId() { return batchId; }
    public UUID getWarehouseId() { return warehouseId; }
    public Integer getQuantity() { return quantity; }
}
