package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
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

    public FulfilmentFacade(FulfilmentService service, FulfilmentOrderRepository orders, WarehouseCatalogService catalog) {
        this.service = service;
        this.orders = orders;
        this.catalog = catalog;
    }

    public FulfilmentDtos.FulfilmentResponse fulfil(FulfilmentService.Command command) {
        try {
            return service.fulfil(command);
        } catch (DataIntegrityViolationException e) {
            // Lost the race for this idempotency key: the winner's order is the answer. Anything else is a real error.
            FulfilmentOrder stored = orders.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> e);
            if (!stored.matchesRequestHash(command.requestHash())) {
                throw new IdempotencyKeyReusedException(command.idempotencyKey());
            }
            return catalog.getFulfilment(stored.getId());
        }
    }
}
