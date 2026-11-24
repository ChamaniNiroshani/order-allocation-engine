package com.chamani.order_allocation_engine.fulfilment;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the fulfilment flow. */
public final class FulfilmentDtos {

    private FulfilmentDtos() {}

    public record CreateWarehouseRequest(
            @NotBlank(message = "name is required") @Size(max = 255, message = "name must be at most 255 characters") String name,
            @NotNull(message = "lat is required")
            @DecimalMin(value = "-90", message = "lat must be between -90 and 90")
            @DecimalMax(value = "90", message = "lat must be between -90 and 90") BigDecimal lat,
            @NotNull(message = "lon is required")
            @DecimalMin(value = "-180", message = "lon must be between -180 and 180")
            @DecimalMax(value = "180", message = "lon must be between -180 and 180") BigDecimal lon) {
    }

    public record WarehouseResponse(UUID id, String name, BigDecimal lat, BigDecimal lon) {
        static WarehouseResponse from(Warehouse w) {
            return new WarehouseResponse(w.getId(), w.getName(), w.getLat(), w.getLon());
        }
    }

    /** expiryDate is optional: leave it out for stock that never expires. */
    public record CreateBatchRequest(
            @NotBlank(message = "sku is required") @Size(max = 64, message = "sku must be at most 64 characters") String sku,
            @NotNull(message = "warehouseId is required") UUID warehouseId,
            @NotNull(message = "qtyAvailable is required") @Min(value = 0, message = "qtyAvailable must not be negative") Integer qtyAvailable,
            LocalDate expiryDate) {
    }

    public record BatchResponse(UUID id, String sku, UUID warehouseId, int qtyAvailable, int qtyReserved,
                                LocalDate expiryDate, Long version) {
        static BatchResponse from(InventoryBatch b) {
            return new BatchResponse(b.getId(), b.getSku(), b.getWarehouseId(), b.getQtyAvailable(), b.getQtyReserved(),
                    b.getExpiryDate(), b.getVersion());
        }
    }

    public record LineResponse(UUID batchId, UUID warehouseId, int quantity) {
        static LineResponse from(FulfilmentLine l) {
            return new LineResponse(l.getBatchId(), l.getWarehouseId(), l.getQuantity());
        }
    }

    public record FulfilmentResponse(UUID id, String sku, int quantity, BigDecimal deliveryLat, BigDecimal deliveryLon,
                                     CustomerTier customerTier, StrategyType strategy, FulfilmentStatus status,
                                     String failureReason, Instant createdAt, List<LineResponse> lines) {
        static FulfilmentResponse from(FulfilmentOrder o, List<FulfilmentLine> lines) {
            return new FulfilmentResponse(o.getId(), o.getSku(), o.getQuantity(), o.getDeliveryLat(), o.getDeliveryLon(),
                    o.getCustomerTier(), o.getStrategy(), o.getStatus(), o.getFailureReason(), o.getCreatedAt(),
                    lines.stream().map(LineResponse::from).toList());
        }
    }
}
