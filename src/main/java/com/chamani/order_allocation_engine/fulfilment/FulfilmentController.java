package com.chamani.order_allocation_engine.fulfilment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The multi-warehouse flow: a fulfilment order and the batches it was served from (docs/adr/0004). */
@RestController
@RequestMapping("/api/fulfilments")
public class FulfilmentController {

    private final WarehouseCatalogService catalog;
    private final FulfilmentFacade facade;

    public FulfilmentController(WarehouseCatalogService catalog, FulfilmentFacade facade) {
        this.catalog = catalog;
        this.facade = facade;
    }

    @GetMapping("/{id}")
    public FulfilmentDtos.FulfilmentResponse get(@PathVariable UUID id) {
        return catalog.getFulfilment(id);
    }

    /** 201 = allocated, 409 = rejected (not enough usable stock). A retry with the same Idempotency-Key gets the stored result and status. */
    @PostMapping
    public ResponseEntity<FulfilmentDtos.FulfilmentResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody FulfilmentDtos.CreateFulfilmentRequest request) {
        FulfilmentDtos.FulfilmentResponse response = facade.fulfil(new FulfilmentService.Command(
                request.sku().trim(), request.quantity(), request.deliveryLat(), request.deliveryLon(),
                request.customerTier(), null, idempotencyKey));
        HttpStatus status = response.status() == FulfilmentStatus.ALLOCATED ? HttpStatus.CREATED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(response);
    }
}
