package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.idempotency.IdempotencyKeys;
import com.chamani.order_allocation_engine.idempotency.IdempotentFlow;
import com.chamani.order_allocation_engine.fulfilment.strategy.AllocationPlan;
import com.chamani.order_allocation_engine.fulfilment.strategy.BatchCandidate;
import com.chamani.order_allocation_engine.fulfilment.strategy.FulfilmentRequest;
import com.chamani.order_allocation_engine.fulfilment.strategy.StrategyRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The multi-warehouse allocation, in ONE transaction:
 * idempotency check, lock every batch of the SKU (ascending batch id), build candidates, let the strategy plan, apply the plan
 * (decrement stock, insert lines) or save a FAILED order with the reason.
 *
 * <p>Trade-off, on purpose: this locks ALL batches of the SKU, not just the ones the plan ends up using, because the plan can
 * only be made once the stock is known, and stock is only stable once it is locked. Planning under the lock means the plan
 * cannot go stale (nobody can take stock between "planned" and "applied"), at the price of serializing every order for the
 * same SKU and holding locks on batches that are not used. Per-SKU contention is the cost; correctness is the gain.
 *
 * <p>Two concurrent first requests with the SAME new idempotency key: the unique constraint rejects the loser at commit, and
 * FulfilmentFacade turns that into a replay. Insufficient stock is a business outcome, not an exception: the FAILED order is
 * committed, so a retry gets the same answer.
 */
@Service
public class FulfilmentService {

    private final InventoryBatchRepository batches;
    private final WarehouseRepository warehouses;
    private final FulfilmentOrderRepository orders;
    private final FulfilmentLineRepository lines;
    private final StrategyRegistry registry;
    private final IdempotencyKeys idempotencyKeys;

    public FulfilmentService(InventoryBatchRepository batches, WarehouseRepository warehouses,
                             FulfilmentOrderRepository orders, FulfilmentLineRepository lines, StrategyRegistry registry,
                             IdempotencyKeys idempotencyKeys) {
        this.idempotencyKeys = idempotencyKeys;
        this.batches = batches;
        this.warehouses = warehouses;
        this.orders = orders;
        this.lines = lines;
        this.registry = registry;
    }

    /** What the caller asked for. {@code requestedStrategy} may be null (then StrategySelector uses the customer tier default). */
    public record Command(String sku, int quantity, BigDecimal deliveryLat, BigDecimal deliveryLon,
                          CustomerTier customerTier, StrategyType requestedStrategy, String idempotencyKey) {

        String requestHash() {
            return FulfilmentRequestHash.of(sku, quantity, deliveryLat, deliveryLon, customerTier, requestedStrategy);
        }
    }

    /** @param strategyType the strategy already chosen for this request (explicit, or the customer tier default) */
    @Transactional
    public FulfilmentDtos.FulfilmentResponse fulfil(Command command, StrategyType strategyType) {
        String hash = command.requestHash();

        // Replay check BEFORE touching stock, so a retried key never decrements twice.
        if (idempotencyKeys.isUsedBy(command.idempotencyKey(), IdempotentFlow.FULFILMENT)) {
            FulfilmentOrder stored = orders.findByIdempotencyKey(command.idempotencyKey()).orElseThrow();
            if (!stored.matchesRequestHash(hash)) {
                throw new IdempotencyKeyReusedException(command.idempotencyKey());
            }
            return FulfilmentDtos.FulfilmentResponse.from(stored, lines.findByFulfilmentOrderIdOrderById(stored.getId()));
        }

        idempotencyKeys.claim(command.idempotencyKey(), IdempotentFlow.FULFILMENT);

        FulfilmentOrder order = new FulfilmentOrder(command.sku(), command.quantity(), command.deliveryLat(), command.deliveryLon(),
                command.customerTier(), strategyType, command.idempotencyKey(), hash);

        List<InventoryBatch> locked = batches.lockBySkuOrderById(command.sku());
        Map<UUID, Warehouse> warehouseById = warehouses.findAllById(
                        locked.stream().map(InventoryBatch::getWarehouseId).distinct().toList())
                .stream().collect(Collectors.toMap(Warehouse::getId, Function.identity()));
        List<BatchCandidate> candidates = locked.stream().map(b -> candidate(b, warehouseById.get(b.getWarehouseId()))).toList();

        FulfilmentRequest request = new FulfilmentRequest(command.sku(), command.quantity(),
                command.deliveryLat().doubleValue(), command.deliveryLon().doubleValue());
        AllocationPlan plan = registry.get(strategyType).plan(request, candidates);

        if (plan.isRejected()) {
            order.markFailed(plan.rejectionReason());
            return FulfilmentDtos.FulfilmentResponse.from(orders.save(order), List.of());
        }

        Map<UUID, InventoryBatch> batchById = locked.stream().collect(Collectors.toMap(InventoryBatch::getId, Function.identity()));
        order.markAllocated();
        FulfilmentOrder saved = orders.save(order);
        List<FulfilmentLine> newLines = new ArrayList<>();
        for (AllocationPlan.Line line : plan.lines()) {
            batchById.get(line.batchId()).take(line.quantity());
            newLines.add(new FulfilmentLine(saved.getId(), line.batchId(), line.warehouseId(), line.quantity()));
        }
        return FulfilmentDtos.FulfilmentResponse.from(saved, lines.saveAll(newLines));
    }

    private static BatchCandidate candidate(InventoryBatch batch, Warehouse warehouse) {
        return new BatchCandidate(batch.getId(), batch.getWarehouseId(), warehouse.getLat().doubleValue(),
                warehouse.getLon().doubleValue(), batch.getQtyAvailable(), batch.getExpiryDate());
    }
}
