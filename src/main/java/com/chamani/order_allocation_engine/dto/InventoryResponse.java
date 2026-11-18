package com.chamani.order_allocation_engine.dto;

import com.chamani.order_allocation_engine.model.InventoryItem;
import java.util.UUID;

public class InventoryResponse {

    private final UUID itemId;
    private final Integer quantityAvailable;
    private final Integer quantityReserved;
    private final Long version;

    public InventoryResponse(UUID itemId, Integer quantityAvailable, Integer quantityReserved, Long version) {
        this.itemId = itemId;
        this.quantityAvailable = quantityAvailable;
        this.quantityReserved = quantityReserved;
        this.version = version;
    }

    public static InventoryResponse from(InventoryItem item) {
        return new InventoryResponse(item.getItemId(), item.getQuantityAvailable(),
                item.getQuantityReserved(), item.getVersion());
    }

    public UUID getItemId() { return itemId; }
    public Integer getQuantityAvailable() { return quantityAvailable; }
    public Integer getQuantityReserved() { return quantityReserved; }
    public Long getVersion() { return version; }
}
