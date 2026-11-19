package com.chamani.order_allocation_engine.dto;

import com.chamani.order_allocation_engine.model.TransactionLog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/** One audit entry. The before/after states are returned as JSON objects, not as escaped strings. */
public class HistoryEntryResponse {

    private final String action;
    private final JsonNode beforeState;
    private final JsonNode afterState;
    private final Instant timestamp;

    public HistoryEntryResponse(String action, JsonNode beforeState, JsonNode afterState, Instant timestamp) {
        this.action = action;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.timestamp = timestamp;
    }

    public static HistoryEntryResponse from(TransactionLog log, ObjectMapper objectMapper) {
        return new HistoryEntryResponse(log.getAction(), parse(log.getBeforeState(), objectMapper),
                parse(log.getAfterState(), objectMapper), log.getTimestamp());
    }

    private static JsonNode parse(String json, ObjectMapper objectMapper) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return objectMapper.getNodeFactory().textNode(json);    // not JSON: show it as plain text
        }
    }

    public String getAction() { return action; }
    public JsonNode getBeforeState() { return beforeState; }
    public JsonNode getAfterState() { return afterState; }
    public Instant getTimestamp() { return timestamp; }
}
