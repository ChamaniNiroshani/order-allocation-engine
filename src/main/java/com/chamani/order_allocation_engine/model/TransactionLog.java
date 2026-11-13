package com.chamani.order_allocation_engine.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transaction_log")
public class TransactionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID logId;

    private String entityType;
    private UUID entityId;
    private String action;

    // columnDefinition = "text": a @Lob String would map to oid in Postgres.
    @Column(columnDefinition = "text")
    private String beforeState;

    @Column(columnDefinition = "text")
    private String afterState;

    private Instant timestamp = Instant.now();

    protected TransactionLog() {}

    public TransactionLog(String entityType, UUID entityId, String action, String beforeState, String afterState) {
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.beforeState = beforeState;
        this.afterState = afterState;
    }

    public UUID getLogId() { return logId; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getAction() { return action; }
    public String getBeforeState() { return beforeState; }
    public String getAfterState() { return afterState; }
    public Instant getTimestamp() { return timestamp; }
}
