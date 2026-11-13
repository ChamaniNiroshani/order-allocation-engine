package com.chamani.order_allocation_engine.concurrency;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** allocation.locking-mode = PESSIMISTIC | OPTIMISTIC | ATOMIC_UPDATE (default PESSIMISTIC). */
@ConfigurationProperties(prefix = "allocation")
public record AllocationProperties(@DefaultValue("PESSIMISTIC") LockingMode lockingMode) {
}
