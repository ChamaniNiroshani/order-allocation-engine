package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
@Tag(name = OpenApiConfig.TAG_FULFILMENT)
@RestController
@RequestMapping("/api/fulfilments")
public class FulfilmentController {

    private final WarehouseCatalogService catalog;
    private final FulfilmentFacade facade;
    private final StrategySelector selector;

    public FulfilmentController(WarehouseCatalogService catalog, FulfilmentFacade facade, StrategySelector selector) {
        this.catalog = catalog;
        this.facade = facade;
        this.selector = selector;
    }

    @Operation(summary = "Get a fulfilment order and the batches it was served from")
    @ApiResponse(responseCode = "200", description = "The fulfilment order",
            content = @Content(schema = @Schema(implementation = FulfilmentDtos.FulfilmentResponse.class)))
    @ApiResponse(responseCode = "400", ref = OpenApiConfig.BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = OpenApiConfig.NOT_FOUND)
    @ApiResponse(responseCode = "503", ref = OpenApiConfig.UNAVAILABLE)
    @GetMapping("/{id}")
    public FulfilmentDtos.FulfilmentResponse get(@PathVariable UUID id) {
        return catalog.getFulfilment(id);
    }

    /** 201 = allocated, 409 = rejected (not enough usable stock). A retry with the same Idempotency-Key gets the stored result and status. */
    @Operation(summary = "Allocate from warehouse batches with the NEAREST, FEFO or SPLIT strategy (idempotent)")
    @ApiResponse(responseCode = "201", description = "Allocated",
            content = @Content(schema = @Schema(implementation = FulfilmentDtos.FulfilmentResponse.class)))
    @ApiResponse(responseCode = "409", description = "Rejected: not enough usable stock. The order is stored as FAILED",
            content = @Content(schema = @Schema(implementation = FulfilmentDtos.FulfilmentResponse.class)))
    @ApiResponse(responseCode = "400", ref = OpenApiConfig.BAD_REQUEST)
    @ApiResponse(responseCode = "415", ref = OpenApiConfig.UNSUPPORTED_MEDIA_TYPE)
    @ApiResponse(responseCode = "422", ref = OpenApiConfig.KEY_REUSED)
    @ApiResponse(responseCode = "503", ref = OpenApiConfig.UNAVAILABLE)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FulfilmentDtos.FulfilmentResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody FulfilmentDtos.CreateFulfilmentRequest request) {
        FulfilmentDtos.FulfilmentResponse response = facade.fulfil(new FulfilmentService.Command(
                request.sku().trim(), request.quantity(), request.deliveryLat(), request.deliveryLon(),
                request.customerTier(), selector.parse(request.strategy()), idempotencyKey));
        HttpStatus status = response.status() == FulfilmentStatus.ALLOCATED ? HttpStatus.CREATED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(response);
    }
}
