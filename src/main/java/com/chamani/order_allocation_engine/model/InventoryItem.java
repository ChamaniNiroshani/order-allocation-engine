package com.chamani.order_allocation_engine.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "inventory_item")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID itemId;

    @Column(nullable = false)
    private Integer quantityAvailable;

    @Column(nullable = false)
    private Integer quantityReserved = 0;

    @Version
    private Long version;

    protected InventoryItem() {}

    public InventoryItem(Integer quantityAvailable) {
        this.quantityAvailable = quantityAvailable;
    }

    public UUID getItemId() { return itemId; }
    public Integer getQuantityAvailable() { return quantityAvailable; }
    public void setQuantityAvailable(Integer quantityAvailable) { this.quantityAvailable = quantityAvailable; }
    public Integer getQuantityReserved() { return quantityReserved; }
    public void setQuantityReserved(Integer quantityReserved) { this.quantityReserved = quantityReserved; }
    public Long getVersion() { return version; }
}
