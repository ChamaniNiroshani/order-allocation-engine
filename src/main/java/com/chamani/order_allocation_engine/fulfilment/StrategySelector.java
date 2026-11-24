package com.chamani.order_allocation_engine.fulfilment;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;

/** Which strategy serves a request: the one the caller asked for, otherwise the default of the customer's tier. */
@Component
public class StrategySelector {

    private final StrategyProperties properties;

    public StrategySelector(StrategyProperties properties) {
        this.properties = properties;
    }

    /** Parses the optional {@code strategy} field. Null means "not given"; anything that is not a strategy name is a 400. */
    public StrategyType parse(String requested) {
        if (requested == null) {
            return null;
        }
        try {
            return StrategyType.valueOf(requested.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidStrategyException("Unknown strategy '" + requested + "', expected one of "
                    + Arrays.toString(StrategyType.values()));
        }
    }

    /** An explicit request wins; without one the tier default applies. */
    public StrategyType select(StrategyType requested, CustomerTier tier) {
        return requested != null ? requested : properties.defaultFor(tier);
    }
}
