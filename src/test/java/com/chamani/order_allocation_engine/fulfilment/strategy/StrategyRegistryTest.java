package com.chamani.order_allocation_engine.fulfilment.strategy;

import com.chamani.order_allocation_engine.fulfilment.StrategyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.chamani.order_allocation_engine.fulfilment.strategy.StrategyFixtures.filter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyRegistryTest {

    private final NearestWarehouseStrategy nearest = new NearestWarehouseStrategy(filter(2));
    private final FefoStrategy fefo = new FefoStrategy(filter(2));
    private final SplitShipmentStrategy split = new SplitShipmentStrategy(filter(2));

    @Test
    void indexesEveryStrategyByItsType() {
        StrategyRegistry registry = new StrategyRegistry(List.of(nearest, fefo, split));

        assertThat(registry.get(StrategyType.NEAREST)).isSameAs(nearest);
        assertThat(registry.get(StrategyType.FEFO)).isSameAs(fefo);
        assertThat(registry.get(StrategyType.SPLIT)).isSameAs(split);
    }

    @Test
    void duplicateType_failsAtStartup() {
        assertThatThrownBy(() -> new StrategyRegistry(List.of(nearest, fefo, split, new NearestWarehouseStrategy(filter(2)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate")
                .hasMessageContaining("NEAREST");
    }

    @Test
    void missingType_failsAtStartup() {
        assertThatThrownBy(() -> new StrategyRegistry(List.of(nearest, split)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AllocationStrategy implementation for FEFO");
        assertThatThrownBy(() -> new StrategyRegistry(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AllocationStrategy implementation for");
    }
}
