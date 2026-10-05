package com.chamani.order_allocation_engine.repository;

import com.chamani.order_allocation_engine.model.TransactionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TransactionLogRepository extends JpaRepository<TransactionLog, UUID> {
    List<TransactionLog> findByEntityIdOrderByTimestampAsc(UUID entityId);
}
