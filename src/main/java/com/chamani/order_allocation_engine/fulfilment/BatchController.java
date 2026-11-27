package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/batches: set-up endpoint for the multi-warehouse flow (docs/adr/0004). */
@Tag(name = OpenApiConfig.TAG_FULFILMENT)
@RestController
@RequestMapping("/api/batches")
public class BatchController {

    private final WarehouseCatalogService catalog;

    public BatchController(WarehouseCatalogService catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "Create a stock batch in a warehouse")
    @ApiResponse(responseCode = "201", description = "Batch created",
            content = @Content(schema = @Schema(implementation = FulfilmentDtos.BatchResponse.class)))
    @ApiResponse(responseCode = "400", ref = OpenApiConfig.BAD_REQUEST)
    @ApiResponse(responseCode = "415", ref = OpenApiConfig.UNSUPPORTED_MEDIA_TYPE)
    @ApiResponse(responseCode = "404", ref = OpenApiConfig.NOT_FOUND)
    @ApiResponse(responseCode = "503", ref = OpenApiConfig.UNAVAILABLE)
    @PostMapping
    public ResponseEntity<FulfilmentDtos.BatchResponse> create(@Valid @RequestBody FulfilmentDtos.CreateBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FulfilmentDtos.BatchResponse.from(catalog.createBatch(request)));
    }
}
