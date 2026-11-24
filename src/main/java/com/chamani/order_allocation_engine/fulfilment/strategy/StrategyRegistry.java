package com.chamani.order_allocation_engine.fulfilment.strategy;

import com.chamani.order_allocation_engine.fulfilment.StrategyType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Spring injects every AllocationStrategy bean; this indexes them by type. The application does not start if a
 * StrategyType has no implementation or has two, so a misconfiguration shows up at deploy time and not on the first request.
 */
@Component
public class StrategyRegistry {

    private final Map<StrategyType, AllocationStrategy> strategies = new EnumMap<>(StrategyType.class);

    public StrategyRegistry(List<AllocationStrategy> implementations) {
        for (AllocationStrategy strategy : implementations) {
            AllocationStrategy previous = strategies.put(strategy.type(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate AllocationStrategy for " + strategy.type() + ": "
                        + previous.getClass().getName() + " and " + strategy.getClass().getName());
            }
        }
        for (StrategyType type : StrategyType.values()) {
            if (!strategies.containsKey(type)) {
                throw new IllegalStateException("No AllocationStrategy implementation for " + type);
            }
        }
    }

    public AllocationStrategy get(StrategyType type) {
        return strategies.get(type);
    }
}
