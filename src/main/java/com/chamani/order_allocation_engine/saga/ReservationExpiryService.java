package com.chamani.order_allocation_engine.saga;

import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.service.OrderAuditService;
import com.chamani.order_allocation_engine.service.StockReservationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * One batch of expiry work per call, each call ONE transaction: lock the batch (SKIP LOCKED), change the
 * orders, move the stock and write the audit rows together. Separate bean from the job so the call goes
 * through the Spring proxy.
 */
@Service
public class ReservationExpiryService {

    private final OrderRepository orderRepository;
    private final StockReservationService stockReservationService;
    private final OrderAuditService orderAudit;

    public ReservationExpiryService(OrderRepository orderRepository,
                                    StockReservationService stockReservationService,
                                    OrderAuditService orderAudit) {
        this.orderRepository = orderRepository;
        this.stockReservationService = stockReservationService;
        this.orderAudit = orderAudit;
    }

    /**
     * RESERVED orders reserved before the cutoff -> EXPIRED, and their stock goes back. Orders a saga step
     * currently holds are skipped (that step is about to decide the order's fate itself).
     *
     * @return how many orders were expired (a full batch means there may be more)
     */
    @Transactional
    public int expireBatch(Instant cutoff, int batchSize) {
        List<Order> stale = orderRepository.lockOlderThan(OrderStatus.RESERVED.name(), cutoff, batchSize);
        for (Order order : stale) {
            OrderAuditService.Snapshot before = orderAudit.snapshot(order);
            stockReservationService.release(order.getItemId(), order.getQuantityRequested());
            order.expire();
            orderAudit.record(order, before);
        }
        return stale.size();
    }

    /**
     * Crash recovery: PAID orders reserved before the cutoff were charged but never confirmed. Finish them:
     * PAID -> CONFIRMED and the reserved units leave the warehouse.
     *
     * @return how many orders were confirmed
     */
    @Transactional
    public int confirmPaidBatch(Instant cutoff, int batchSize) {
        List<Order> stuck = orderRepository.lockOlderThan(OrderStatus.PAID.name(), cutoff, batchSize);
        for (Order order : stuck) {
            OrderAuditService.Snapshot before = orderAudit.snapshot(order);
            stockReservationService.confirm(order.getItemId(), order.getQuantityRequested());
            order.confirm();
            orderAudit.record(order, before);
        }
        return stuck.size();
    }
}
