package com.chamani.order_allocation_engine.idempotency;

import com.chamani.order_allocation_engine.exception.IdempotencyKeyReusedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Which flow each Idempotency-Key belongs to (table idempotency_key). A flow asks here BEFORE it looks for a stored
 * record, so it can never be answered with, or act on, another flow's record. A claim and the record it stands for are
 * committed together: if a claim is visible, so is the record.
 */
@Component
public class IdempotencyKeys {

    private final JdbcTemplate jdbc;

    public IdempotencyKeys(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true if this flow already used the key (its stored record is the answer), false if no flow has
     * @throws IdempotencyKeyReusedException if another flow used the key
     */
    public boolean isUsedBy(String key, IdempotentFlow flow) {
        List<String> owner = jdbc.queryForList(
                "SELECT flow FROM idempotency_key WHERE idempotency_key = ?", String.class, key);
        if (owner.isEmpty()) {
            return false;
        }
        IdempotentFlow owningFlow = IdempotentFlow.valueOf(owner.get(0));
        if (owningFlow != flow) {
            throw IdempotencyKeyReusedException.byAnotherFlow(key, owningFlow);
        }
        return true;
    }

    /**
     * Takes the key for this flow, as part of the caller's transaction: it is released again if that rolls back. While
     * another transaction holds an uncommitted claim on the same key this waits for it, and if that one commits it throws
     * a DataIntegrityViolationException, which the callers turn into a replay or a refusal.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claim(String key, IdempotentFlow flow) {
        jdbc.update("INSERT INTO idempotency_key (idempotency_key, flow) VALUES (?, ?)", key, flow.name());
    }
}
