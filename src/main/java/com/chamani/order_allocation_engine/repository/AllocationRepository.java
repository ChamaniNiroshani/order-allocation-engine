package com.chamani.order_allocation_engine.repository;

import com.chamani.order_allocation_engine.model.Allocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AllocationRepository extends JpaRepository<Allocation, UUID> {
}
