package com.chamani.order_allocation_engine.fulfilment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.EnumMap;
import java.util.Map;

/**
 * allocation.strategy.tier-defaults.PRIORITY=NEAREST and allocation.strategy.tier-defaults.STANDARD=FEFO: the strategy used when
 * a request does not name one. Checked at startup: an unknown strategy name fails the binding, and every customer tier must have a default.
 */
@Validated
@ConfigurationProperties(prefix = "allocation.strategy")
public record StrategyProperties(Map<CustomerTier, StrategyType> tierDefaults) {

    public StrategyProperties {
        Map<CustomerTier, StrategyType> copy = new EnumMap<>(CustomerTier.class);
        if (tierDefaults != null) {
            copy.putAll(tierDefaults);
        }
        for (CustomerTier tier : CustomerTier.values()) {
            if (copy.get(tier) == null) {
                throw new IllegalArgumentException("allocation.strategy.tier-defaults." + tier + " is required (one of "
                        + java.util.Arrays.toString(StrategyType.values()) + ")");
            }
        }
        tierDefaults = Map.copyOf(copy);
    }

    public StrategyType defaultFor(CustomerTier tier) {
        return tierDefaults.get(tier);
    }
}
