package com.chamani.order_allocation_engine.fulfilment;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/batches: set-up endpoint for the multi-warehouse flow (docs/adr/0004). */
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final WarehouseCatalogService catalog;

    public BatchController(WarehouseCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping
    public ResponseEntity<FulfilmentDtos.BatchResponse> create(@Valid @RequestBody FulfilmentDtos.CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FulfilmentDtos.BatchResponse.from(catalog.createBatch(request)));
    }
}
