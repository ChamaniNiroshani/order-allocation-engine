package com.chamani.order_allocation_engine.fulfilment;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "warehouse")
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lon;

    protected Warehouse() {}

    public Warehouse(String name, BigDecimal lat, BigDecimal lon) {
        this.name = name;
        this.lat = lat;
        this.lon = lon;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public BigDecimal getLat() { return lat; }
    public BigDecimal getLon() { return lon; }
}
