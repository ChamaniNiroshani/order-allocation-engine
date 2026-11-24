package com.chamani.order_allocation_engine.fulfilment;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface FulfilmentLineRepository extends JpaRepository<FulfilmentLine, UUID> {

    List<FulfilmentLine> findByFulfilmentOrderIdOrderById(UUID fulfilmentOrderId);
}
