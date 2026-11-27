package com.chamani.order_allocation_engine.saga;

import com.chamani.order_allocation_engine.idempotency.IdempotencyKeys;
import com.chamani.order_allocation_engine.idempotency.IdempotentFlow;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.repository.InventoryItemRepository;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.service.OrderAuditService;
import com.chamani.order_allocation_engine.service.StockReservationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The saga's database steps. Each method is ONE short transaction and lives on its own bean, so the
 * orchestrator's calls go through the Spring proxy (a self-call would skip @Transactional).
 *
 * Every step that changes an order first locks its row (SELECT ... FOR UPDATE) and then checks the
 * current status. That is what stops the saga and the expiry job from both acting on one order, and
 * it makes compensation idempotent: stock is released only on a RESERVED -> CANCELLED/EXPIRED move.
 */
@Service
public class CheckoutSteps {

    /** What step 1 produced. {@code amount} is null unless the order is RESERVED. */
    public record Reservation(Order order, BigDecimal amount) {}

    /** Whether the payment could be recorded on the order. */
    public enum PaymentRecording { RECORDED, TOO_LATE }

    private final OrderRepository orderRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockReservationService stockReservationService;
    private final OrderAuditService orderAudit;
    private final IdempotencyKeys idempotencyKeys;

    public CheckoutSteps(OrderRepository orderRepository,
                         InventoryItemRepository inventoryItemRepository,
                         StockReservationService stockReservationService,
                         OrderAuditService orderAudit,
                         IdempotencyKeys idempotencyKeys) {
        this.idempotencyKeys = idempotencyKeys;
        this.orderRepository = orderRepository;
        this.inventoryItemRepository = inventoryItemRepository;
        this.stockReservationService = stockReservationService;
        this.orderAudit = orderAudit;
    }

    /**
     * Step 1: create the order PENDING, reserve the stock, move to RESERVED (or FAILED if there is not
     * enough). The idempotency key is claimed and the order inserted first, so a concurrent request with the
     * same key hits the unique constraint before it can reserve anything.
     *
     * @throws IllegalArgumentException if the item does not exist (nothing is saved)
     */
    @Transactional
    public Reservation reserve(UUID itemId, int quantity, String idempotencyKey, String requestHash) {
        idempotencyKeys.claim(idempotencyKey, IdempotentFlow.CHECKOUT);
        Order order = orderRepository.saveAndFlush(new Order(itemId, quantity, idempotencyKey, requestHash));
        OrderAuditService.Snapshot before = orderAudit.snapshot(order);

        if (!stockReservationService.reserve(itemId, quantity)) {
            order.markFailed(CheckoutOutcomes.REASON_INSUFFICIENT);
            orderAudit.record(order, before);
            return new Reservation(order, null);
        }
        order.reserve();
        orderAudit.record(order, before);

        BigDecimal unitPrice = inventoryItemRepository.findById(itemId).orElseThrow().getUnitPrice();
        return new Reservation(order, unitPrice.multiply(BigDecimal.valueOf(quantity)));
    }

    /**
     * Step 3a, part 1: the gateway approved. RESERVED -> PAID, committed on its own so a crash right
     * after still leaves a PAID order the recovery job can finish. TOO_LATE if the order was already
     * expired or cancelled: the caller must refund.
     */
    @Transactional
    public PaymentRecording recordPayment(UUID orderId, String paymentId) {
        Order order = lock(orderId);
        switch (order.getStatus()) {
            case RESERVED -> {
                OrderAuditService.Snapshot before = orderAudit.snapshot(order);
                order.markPaid(paymentId);
                orderAudit.record(order, before);
                return PaymentRecording.RECORDED;
            }
            case PAID, CONFIRMED -> {
                return PaymentRecording.RECORDED;       // already recorded (a retry or the recovery job got here first)
            }
            case EXPIRED, CANCELLED -> {
                return PaymentRecording.TOO_LATE;
            }
            default -> throw new IllegalStateException("Cannot record a payment on a " + order.getStatus() + " order");
        }
    }

    /** Step 3a, part 2: PAID -> CONFIRMED and the reserved units leave the warehouse. A no-op if already CONFIRMED. */
    @Transactional
    public Order confirm(UUID orderId) {
        Order order = lock(orderId);
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            return order;
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Cannot confirm a " + order.getStatus() + " order");
        }
        OrderAuditService.Snapshot before = orderAudit.snapshot(order);
        stockReservationService.confirm(order.getItemId(), order.getQuantityRequested());
        order.confirm();
        orderAudit.record(order, before);
        return order;
    }

    /**
     * Step 3b / 3c: RESERVED -> CANCELLED and release the stock. Idempotent: if the order is no longer
     * RESERVED (already cancelled, or expired by the job) nothing is released a second time.
     */
    @Transactional
    public Order compensate(UUID orderId, String reason) {
        Order order = lock(orderId);
        if (order.getStatus() == OrderStatus.RESERVED) {
            OrderAuditService.Snapshot before = orderAudit.snapshot(order);
            stockReservationService.release(order.getItemId(), order.getQuantityRequested());
            order.cancel(reason);
            orderAudit.record(order, before);
        }
        return order;
    }

    /** The payment of an expired or cancelled order was refunded: say so in the order's reason. */
    @Transactional
    public Order recordRefund(UUID orderId) {
        Order order = lock(orderId);
        OrderAuditService.Snapshot before = orderAudit.snapshot(order);
        order.recordRefund();
        orderAudit.record(order, before, OrderAuditService.ACTION_PAYMENT_REFUNDED);
        return order;
    }

    private Order lock(UUID orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalStateException("Order vanished mid-saga: " + orderId));
    }
}
