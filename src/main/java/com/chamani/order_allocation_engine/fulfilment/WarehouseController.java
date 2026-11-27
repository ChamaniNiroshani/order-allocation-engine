package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /api/warehouses: set-up endpoint for the multi-warehouse flow (docs/adr/0004). */
@Tag(name = OpenApiConfig.TAG_FULFILMENT)
@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehouseCatalogService catalog;

    public WarehouseController(WarehouseCatalogService catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "Create a warehouse")
    @ApiResponse(responseCode = "201", description = "Warehouse created",
            content = @Content(schema = @Schema(implementation = FulfilmentDtos.WarehouseResponse.class)))
    @ApiResponse(responseCode = "400", ref = OpenApiConfig.BAD_REQUEST)
    @ApiResponse(responseCode = "415", ref = OpenApiConfig.UNSUPPORTED_MEDIA_TYPE)
    @ApiResponse(responseCode = "503", ref = OpenApiConfig.UNAVAILABLE)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FulfilmentDtos.WarehouseResponse> create(@Valid @RequestBody FulfilmentDtos.CreateWarehouseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FulfilmentDtos.WarehouseResponse.from(catalog.createWarehouse(request)));
    }
}
