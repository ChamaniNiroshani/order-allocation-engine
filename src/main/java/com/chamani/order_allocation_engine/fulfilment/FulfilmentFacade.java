package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.idempotency.IdempotencyKeys;
import com.chamani.order_allocation_engine.idempotency.IdempotentFlow;
import com.chamani.order_allocation_engine.metrics.AllocationMetrics;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT @Transactional and a separate bean from FulfilmentService (same reason as OrderAllocationFacade): the catch
 * must run after the FulfilmentService proxy has committed or rolled back, and a unique-constraint violation only surfaces at commit.
 */
@Service
public class FulfilmentFacade {

    private final FulfilmentService service;
    private final FulfilmentOrderRepository orders;
    private final WarehouseCatalogService catalog;
    private final StrategySelector selector;
    private final AllocationMetrics metrics;
    private final IdempotencyKeys idempotencyKeys;

    public FulfilmentFacade(FulfilmentService service, FulfilmentOrderRepository orders, WarehouseCatalogService catalog,
                            StrategySelector selector, AllocationMetrics metrics, IdempotencyKeys idempotencyKeys) {
        this.idempotencyKeys = idempotencyKeys;
        this.service = service;
        this.orders = orders;
        this.catalog = catalog;
        this.selector = selector;
        this.metrics = metrics;
    }

    public FulfilmentDtos.FulfilmentResponse fulfil(FulfilmentService.Command command) {
        StrategyType strategy = selector.select(command.requestedStrategy(), command.customerTier());
        return metrics.measureFulfilment(strategy, () -> doFulfil(command, strategy));
    }

    private FulfilmentDtos.FulfilmentResponse doFulfil(FulfilmentService.Command command, StrategyType strategy) {
        try {
            return service.fulfil(command, strategy);
        } catch (DataIntegrityViolationException e) {
            // Lost the race for this idempotency key: the winner's order is the answer, unless another flow won it
            // (refused). Anything else is a real error.
            idempotencyKeys.isUsedBy(command.idempotencyKey(), IdempotentFlow.FULFILMENT);
            FulfilmentOrder stored = orders.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> e);
            if (!stored.matchesRequestHash(command.requestHash())) {
                throw new IdempotencyKeyReusedException(command.idempotencyKey());
            }
            return catalog.getFulfilment(stored.getId());
        }
    }
}
