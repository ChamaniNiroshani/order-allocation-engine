package com.chamani.order_allocation_engine.fulfilment;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/warehouses: set-up endpoint for the multi-warehouse flow (docs/adr/0004). */
@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehouseCatalogService catalog;

    public WarehouseController(WarehouseCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    public ResponseEntity<FulfilmentDtos.WarehouseResponse> create(@Valid @RequestBody FulfilmentDtos.CreateWarehouseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FulfilmentDtos.WarehouseResponse.from(catalog.createWarehouse(request)));
    }
}
