package com.chamani.order_allocation_engine.controller;

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

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping
    public ResponseEntity<InventoryResponse> create(@Valid @RequestBody CreateInventoryRequest request) {
        InventoryResponse body = InventoryResponse.from(inventoryService.create(request.getQuantityAvailable()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public InventoryResponse get(@PathVariable UUID id) {
        return InventoryResponse.from(inventoryService.get(id));
    }
}
