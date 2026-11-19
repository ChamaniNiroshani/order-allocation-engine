-- GET /api/orders/{id}/history reads the log by entity, oldest first.
CREATE INDEX idx_transaction_log_entity ON transaction_log (entity_id, "timestamp");
