package com.chamani.order_allocation_engine.fulfilment.strategy;

import java.time.LocalDate;
import java.util.UUID;

/** A batch a strategy may take stock from, with its warehouse's position. A snapshot, not a JPA entity. expiryDate null = never expires. */
public record BatchCandidate(UUID batchId, UUID warehouseId, double warehouseLat, double warehouseLon,
                             int quantityAvailable, LocalDate expiryDate) {
}
