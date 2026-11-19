package com.chamani.order_allocation_engine.saga;

import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.logging.OrderEvents;
import com.chamani.order_allocation_engine.logging.OrderMdc;
import com.chamani.order_allocation_engine.metrics.AllocationMetrics;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.payment.PaymentGateway;
import com.chamani.order_allocation_engine.payment.PaymentResult;
import com.chamani.order_allocation_engine.payment.PaymentTimeoutException;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.service.RequestHash;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Orchestrates the checkout saga: reserve stock, charge, confirm, and compensate when a step fails.
 *
 * Deliberately NOT @Transactional: each database step is its own short transaction on {@link CheckoutSteps},
 * and the payment call in between runs with no transaction and no row lock open.
 */
@Service
public class CheckoutOrchestrator {

    private final CheckoutSteps steps;
    private final PaymentGateway paymentGateway;
    private final OrderRepository orderRepository;
    private final AllocationMetrics metrics;

    public CheckoutOrchestrator(CheckoutSteps steps, PaymentGateway paymentGateway, OrderRepository orderRepository,
                                AllocationMetrics metrics) {
        this.steps = steps;
        this.paymentGateway = paymentGateway;
        this.orderRepository = orderRepository;
        this.metrics = metrics;
    }

    public Order checkout(UUID itemId, int quantity, String idempotencyKey) {
        return metrics.measure(AllocationMetrics.Flow.CHECKOUT, () -> run(itemId, quantity, idempotencyKey));
    }

    private Order run(UUID itemId, int quantity, String idempotencyKey) {
        String requestHash = RequestHash.of(itemId, quantity);

        // 0. Idempotency: a known key returns its stored order as-is. No new reservation, no new charge.
        var existing = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, idempotencyKey);
        }

        // 1. Reserve stock (own transaction, commits).
        CheckoutSteps.Reservation reservation;
        try {
            reservation = steps.reserve(itemId, quantity, idempotencyKey, requestHash);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same key inserted its order first; ours rolled back, nothing is reserved.
            Order stored = orderRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            OrderEvents.idempotentReplay(OrderEvents.FLOW_CHECKOUT, stored.getOrderId());
            return replay(stored, requestHash, idempotencyKey);
        }
        Order order = reservation.order();
        if (order.getStatus() != OrderStatus.RESERVED) {
            OrderEvents.rejected(OrderEvents.FLOW_CHECKOUT, order);        // FAILED: not enough stock
            return order;
        }
        try (var ignored = OrderMdc.put(order.getOrderId())) {          // everything the saga logs from here on carries the order id
            return continueSaga(order, reservation.amount(), idempotencyKey);
        }
    }

    /** Steps 2 and 3: charge, then confirm or compensate. The order is RESERVED and its id is in the log context. */
    private Order continueSaga(Order order, BigDecimal amount, String idempotencyKey) {
        UUID orderId = order.getOrderId();

        // 2. Charge. NO transaction and no row lock is open here.
        PaymentResult result;
        try {
            result = paymentGateway.charge(orderId, amount, idempotencyKey);
        } catch (PaymentTimeoutException e) {
            // 3c. Unknown whether the charge happened. Refunding is safe: the gateway dedupes on the key,
            // so refunding a key that was never charged does nothing.
            paymentGateway.refund(idempotencyKey);
            metrics.refund();
            OrderEvents.refunded(order, AllocationMetrics.REASON_TIMEOUT);
            return compensate(orderId, CheckoutOutcomes.REASON_TIMEOUT, AllocationMetrics.REASON_TIMEOUT);
        }

        if (!result.isApproved()) {
            // 3b. Declined: nothing was charged, give the stock back.
            return compensate(orderId, CheckoutOutcomes.REASON_DECLINED + ": " + result.reason(), AllocationMetrics.REASON_DECLINED);
        }

        // 3a. Approved. Lock the order and see whether it is still ours.
        if (steps.recordPayment(orderId, result.paymentId()) == CheckoutSteps.PaymentRecording.TOO_LATE) {
            // The expiry job released the stock while the payment was in flight: give the money back.
            paymentGateway.refund(idempotencyKey);
            metrics.refund();
            Order refunded = steps.recordRefund(orderId);
            OrderEvents.refunded(refunded, AllocationMetrics.REASON_EXPIRED);
            return refunded;
        }
        Order confirmed = steps.confirm(orderId);
        OrderEvents.allocated(OrderEvents.FLOW_CHECKOUT, confirmed);
        return confirmed;
    }

    /** Counts the compensation only if THIS call cancelled the order (the expiry job may have expired it first and counted it). */
    private Order compensate(UUID orderId, String reason, String metricReason) {
        Order order = steps.compensate(orderId, reason);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            metrics.compensation(metricReason);
            OrderEvents.compensated(order, metricReason);
        }
        return order;
    }

    private Order replay(Order stored, String requestHash, String idempotencyKey) {
        try (var ignored = OrderMdc.put(stored.getOrderId())) {   // the order was loaded: its id is the context of anything logged here
            if (!stored.matchesRequestHash(requestHash)) {
                throw new IdempotencyKeyReusedException(idempotencyKey);
            }
            return stored;
        }
    }
}
