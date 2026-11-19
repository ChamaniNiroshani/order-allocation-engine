package com.chamani.order_allocation_engine.repository;

import com.chamani.order_allocation_engine.model.TransactionLog;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only: it extends the bare {@link Repository} marker, not JpaRepository, so there is no
 * delete, update-by-id or deleteAll to call. Rows are only ever inserted and read.
 */
public interface TransactionLogRepository extends Repository<TransactionLog, UUID> {

    <S extends TransactionLog> S save(S entry);

    List<TransactionLog> findByEntityIdOrderByTimestampAsc(UUID entityId);
}
