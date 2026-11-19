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
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Orchestrates the checkout saga: reserve stock, charge, confirm, and compensate when a step fails.
 *
 * Deliberately NOT @Transactional: each database step is its own short transaction on {@link CheckoutSteps},
 * and the payment call in between runs with no transaction and no row lock open.
 *
 * Every step and every payment gateway call is an observation, so a trace shows the saga as
 * http request -> saga.reserve | saga.charge -> payment.gateway.charge | saga.confirm | saga.compensate -> payment.gateway.refund,
 * with the database queries each step runs underneath it. The order id is a high-cardinality key/value:
 * it goes on the span, never on a metric.
 */
@Service
public class CheckoutOrchestrator {

    public static final String STEP_RESERVE = "saga.reserve";
    public static final String STEP_CHARGE = "saga.charge";
    public static final String STEP_CONFIRM = "saga.confirm";
    public static final String STEP_COMPENSATE = "saga.compensate";
    public static final String GATEWAY_CHARGE = "payment.gateway.charge";
    public static final String GATEWAY_REFUND = "payment.gateway.refund";

    /** What the charge step produced: the gateway's answer and, if it approved, whether the payment could be recorded. */
    private record Charge(PaymentResult result, CheckoutSteps.PaymentRecording recording) {}

    private final CheckoutSteps steps;
    private final PaymentGateway paymentGateway;
    private final OrderRepository orderRepository;
    private final AllocationMetrics metrics;
    private final ObservationRegistry observations;

    public CheckoutOrchestrator(CheckoutSteps steps, PaymentGateway paymentGateway, OrderRepository orderRepository,
                                AllocationMetrics metrics, ObservationRegistry observations) {
        this.steps = steps;
        this.paymentGateway = paymentGateway;
        this.orderRepository = orderRepository;
        this.metrics = metrics;
        this.observations = observations;
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
            reservation = observe(STEP_RESERVE, "item.id", itemId,
                    () -> steps.reserve(itemId, quantity, idempotencyKey, requestHash));
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

        // 2. Charge. NO transaction and no row lock is open during the gateway call. If it approves, the payment is
        // then recorded in its own short transaction (RESERVED -> PAID), so a crash right after is recoverable.
        Charge charge;
        try {
            charge = observe(STEP_CHARGE, "order.id", orderId, () -> {
                PaymentResult paid = observe(GATEWAY_CHARGE, "order.id", orderId,
                        () -> paymentGateway.charge(orderId, amount, idempotencyKey));
                return new Charge(paid, paid.isApproved() ? steps.recordPayment(orderId, paid.paymentId()) : null);
            });
        } catch (PaymentTimeoutException e) {
            // 3c. Unknown whether the charge happened. Refunding is safe: the gateway dedupes on the key,
            // so refunding a key that was never charged does nothing.
            return observe(STEP_COMPENSATE, "order.id", orderId, () -> {
                refund(orderId, idempotencyKey);
                OrderEvents.refunded(order, AllocationMetrics.REASON_TIMEOUT);
                return compensate(orderId, CheckoutOutcomes.REASON_TIMEOUT, AllocationMetrics.REASON_TIMEOUT);
            });
        }

        if (!charge.result().isApproved()) {
            // 3b. Declined: nothing was charged, give the stock back.
            return observe(STEP_COMPENSATE, "order.id", orderId, () ->
                    compensate(orderId, CheckoutOutcomes.REASON_DECLINED + ": " + charge.result().reason(),
                            AllocationMetrics.REASON_DECLINED));
        }

        if (charge.recording() == CheckoutSteps.PaymentRecording.TOO_LATE) {
            // The expiry job released the stock while the payment was in flight: give the money back.
            return observe(STEP_COMPENSATE, "order.id", orderId, () -> {
                refund(orderId, idempotencyKey);
                Order refunded = steps.recordRefund(orderId);
                OrderEvents.refunded(refunded, AllocationMetrics.REASON_EXPIRED);
                return refunded;
            });
        }

        // 3a. Approved and recorded: PAID -> CONFIRMED, and the reserved units leave the warehouse.
        return observe(STEP_CONFIRM, "order.id", orderId, () -> {
            Order confirmed = steps.confirm(orderId);
            OrderEvents.allocated(OrderEvents.FLOW_CHECKOUT, confirmed);
            return confirmed;
        });
    }

    private void refund(UUID orderId, String idempotencyKey) {
        observe(GATEWAY_REFUND, "order.id", orderId, () -> {
            paymentGateway.refund(idempotencyKey);
            return null;
        });
        metrics.refund();
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

    /**
     * Runs the action inside an observation. An exception it throws is recorded on the span and rethrown. The id
     * goes on as a high-cardinality key/value: visible on the span, but never a tag on the metric the observation also makes.
     */
    private <T> T observe(String name, String idKey, Object id, Supplier<T> action) {
        return Observation.createNotStarted(name, observations)
                .highCardinalityKeyValue(idKey, String.valueOf(id))
                .observe(action);
    }
}
