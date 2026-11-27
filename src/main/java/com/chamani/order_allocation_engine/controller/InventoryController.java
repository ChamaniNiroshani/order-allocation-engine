package com.chamani.order_allocation_engine.controller;

import com.chamani.order_allocation_engine.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.chamani.order_allocation_engine.dto.CreateInventoryRequest;
import com.chamani.order_allocation_engine.dto.InventoryResponse;
import com.chamani.order_allocation_engine.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = OpenApiConfig.TAG_INVENTORY)
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Operation(summary = "Create a stock item")
    @ApiResponse(responseCode = "201", description = "Item created",
            content = @Content(schema = @Schema(implementation = InventoryResponse.class)))
    @ApiResponse(responseCode = "400", ref = OpenApiConfig.BAD_REQUEST)
    @ApiResponse(responseCode = "415", ref = OpenApiConfig.UNSUPPORTED_MEDIA_TYPE)
    @ApiResponse(responseCode = "503", ref = OpenApiConfig.UNAVAILABLE)
    @PostMapping
    public ResponseEntity<InventoryResponse> create(@Valid @RequestBody CreateInventoryRequest request) {
        InventoryResponse body = InventoryResponse.from(inventoryService.create(request.getQuantityAvailable()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @Operation(summary = "Get a stock item")
    @ApiResponse(responseCode = "200", description = "The item",
            content = @Content(schema = @Schema(implementation = InventoryResponse.class)))
    @ApiResponse(responseCode = "400", ref = OpenApiConfig.BAD_REQUEST)
    @ApiResponse(responseCode = "404", ref = OpenApiConfig.NOT_FOUND)
    @ApiResponse(responseCode = "503", ref = OpenApiConfig.UNAVAILABLE)
    @GetMapping("/{id}")
    public InventoryResponse get(@PathVariable UUID id) {
        return InventoryResponse.from(inventoryService.get(id));
    }
}
