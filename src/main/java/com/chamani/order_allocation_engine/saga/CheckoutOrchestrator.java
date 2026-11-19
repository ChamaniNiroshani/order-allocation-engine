package com.chamani.order_allocation_engine.saga;

import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.payment.PaymentGateway;
import com.chamani.order_allocation_engine.payment.PaymentResult;
import com.chamani.order_allocation_engine.payment.PaymentTimeoutException;
import com.chamani.order_allocation_engine.repository.OrderRepository;
import com.chamani.order_allocation_engine.service.RequestHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CheckoutOrchestrator.class);

    private final CheckoutSteps steps;
    private final PaymentGateway paymentGateway;
    private final OrderRepository orderRepository;

    public CheckoutOrchestrator(CheckoutSteps steps, PaymentGateway paymentGateway, OrderRepository orderRepository) {
        this.steps = steps;
        this.paymentGateway = paymentGateway;
        this.orderRepository = orderRepository;
    }

    public Order checkout(UUID itemId, int quantity, String idempotencyKey) {
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
            log.info("Idempotency conflict for key {}, returning the stored order", idempotencyKey);
            Order stored = orderRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
            return replay(stored, requestHash, idempotencyKey);
        }
        Order order = reservation.order();
        if (order.getStatus() != OrderStatus.RESERVED) {
            return order;                                    // FAILED: not enough stock
        }
        UUID orderId = order.getOrderId();

        // 2. Charge. NO transaction and no row lock is open here.
        PaymentResult result;
        try {
            result = paymentGateway.charge(orderId, reservation.amount(), idempotencyKey);
        } catch (PaymentTimeoutException e) {
            // 3c. Unknown whether the charge happened. Refunding is safe: the gateway dedupes on the key,
            // so refunding a key that was never charged does nothing.
            log.warn("Payment timed out for order {}, refunding and compensating", orderId);
            paymentGateway.refund(idempotencyKey);
            return steps.compensate(orderId, CheckoutOutcomes.REASON_TIMEOUT);
        }

        if (!result.isApproved()) {
            // 3b. Declined: nothing was charged, give the stock back.
            return steps.compensate(orderId, CheckoutOutcomes.REASON_DECLINED + ": " + result.reason());
        }

        // 3a. Approved. Lock the order and see whether it is still ours.
        if (steps.recordPayment(orderId, result.paymentId()) == CheckoutSteps.PaymentRecording.TOO_LATE) {
            // The expiry job released the stock while the payment was in flight: give the money back.
            log.warn("Order {} expired while its payment was in flight, refunding", orderId);
            paymentGateway.refund(idempotencyKey);
            return steps.recordRefund(orderId);
        }
        return steps.confirm(orderId);
    }

    private Order replay(Order stored, String requestHash, String idempotencyKey) {
        if (!stored.matchesRequestHash(requestHash)) {
            throw new IdempotencyKeyReusedException(idempotencyKey);
        }
        return stored;
    }
}
