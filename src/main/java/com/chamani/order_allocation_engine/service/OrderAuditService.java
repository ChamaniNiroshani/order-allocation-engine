package com.chamani.order_allocation_engine.service;

import com.chamani.order_allocation_engine.model.Order;
import com.chamani.order_allocation_engine.model.OrderStatus;
import com.chamani.order_allocation_engine.model.TransactionLog;
import com.chamani.order_allocation_engine.repository.TransactionLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the append-only audit trail for orders. {@link #record} requires a running transaction
 * (MANDATORY), so a log row can only ever be written in the SAME transaction as the change it
 * describes: if that rolls back, the row goes with it. There is deliberately no update or delete.
 *
 * Usage: take a {@link Snapshot} before changing the order, change it, then call {@link #record}.
 */
@Service
public class OrderAuditService {

    public static final String ENTITY_TYPE = "ORDER";
    public static final String ACTION_PAYMENT_REFUNDED = "PAYMENT_REFUNDED";
    public static final String ACTION_REFUND_REQUESTED = "REFUND_REQUESTED";

    /** The order's state before a change: its status (for the action name) and a small JSON document. */
    public record Snapshot(OrderStatus status, String json) {}

    private final TransactionLogRepository transactionLogRepository;
    private final ObjectMapper objectMapper;

    public OrderAuditService(TransactionLogRepository transactionLogRepository, ObjectMapper objectMapper) {
        this.transactionLogRepository = transactionLogRepository;
        this.objectMapper = objectMapper;
    }

    public Snapshot snapshot(Order order) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", order.getStatus().name());
        state.put("quantity", order.getQuantityRequested());
        if (order.getPaymentId() != null) {
            state.put("paymentId", order.getPaymentId());
        }
        if (order.getFailureReason() != null) {
            state.put("reason", order.getFailureReason());
        }
        try {
            return new Snapshot(order.getStatus(), objectMapper.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize the order state for the audit log", e);
        }
    }

    /** Records a status change, named {@code BEFORE->AFTER}, e.g. {@code RESERVED->PAID}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Order order, Snapshot before) {
        record(order, before, before.status() + "->" + order.getStatus());
    }

    /** Records a change with an explicit action name (for events that are not a status change). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Order order, Snapshot before, String action) {
        transactionLogRepository.save(new TransactionLog(
                ENTITY_TYPE, order.getOrderId(), action, before.json(), snapshot(order).json()));
    }
}
