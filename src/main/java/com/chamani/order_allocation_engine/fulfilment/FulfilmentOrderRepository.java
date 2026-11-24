package com.chamani.order_allocation_engine.fulfilment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface FulfilmentOrderRepository extends JpaRepository<FulfilmentOrder, UUID> {

    Optional<FulfilmentOrder> findByIdempotencyKey(String idempotencyKey);
}
