package com.chamani.order_allocation_engine.fulfilment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The multi-warehouse flow: a fulfilment order and the batches it was served from (docs/adr/0004). */
@RestController
@RequestMapping("/api/fulfilments")
public class FulfilmentController {

    private final WarehouseCatalogService catalog;

    public FulfilmentController(WarehouseCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{id}")
    public FulfilmentDtos.FulfilmentResponse get(@PathVariable UUID id) {
        return catalog.getFulfilment(id);
    }
}
