package com.chamani.order_allocation_engine.fulfilment;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Set-up and read access for warehouses, batches and fulfilment orders. The allocation itself is in FulfilmentService. */
@Service
public class WarehouseCatalogService {

    private final WarehouseRepository warehouses;
    private final InventoryBatchRepository batches;
    private final FulfilmentOrderRepository orders;
    private final FulfilmentLineRepository lines;

    public WarehouseCatalogService(WarehouseRepository warehouses, InventoryBatchRepository batches,
                                   FulfilmentOrderRepository orders, FulfilmentLineRepository lines) {
        this.warehouses = warehouses;
        this.batches = batches;
        this.orders = orders;
        this.lines = lines;
    }

    @Transactional
    public Warehouse createWarehouse(FulfilmentDtos.CreateWarehouseRequest request) {
        return warehouses.save(new Warehouse(request.name().trim(), request.lat(), request.lon()));
    }

    /** @throws IllegalArgumentException (answered as 404) if the warehouse does not exist */
    @Transactional
    public InventoryBatch createBatch(FulfilmentDtos.CreateBatchRequest request) {
        if (!warehouses.existsById(request.warehouseId())) {
            throw new IllegalArgumentException("Warehouse not found: " + request.warehouseId());
        }
        return batches.save(new InventoryBatch(request.sku().trim(), request.warehouseId(), request.qtyAvailable(),
                request.expiryDate()));
    }

    /** @throws IllegalArgumentException (answered as 404) if there is no such fulfilment order */
    @Transactional(readOnly = true)
    public FulfilmentDtos.FulfilmentResponse getFulfilment(UUID id) {
        FulfilmentOrder order = orders.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Fulfilment order not found: " + id));
        List<FulfilmentLine> orderLines = lines.findByFulfilmentOrderIdOrderById(id);
        return FulfilmentDtos.FulfilmentResponse.from(order, orderLines);
    }
}
