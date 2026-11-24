package com.chamani.order_allocation_engine.fulfilment.strategy;

import com.chamani.order_allocation_engine.fulfilment.StrategyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.chamani.order_allocation_engine.fulfilment.strategy.StrategyFixtures.filter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyRegistryTest {

    @Test
    void indexesEveryStrategyByItsType() {
        NearestWarehouseStrategy nearest = new NearestWarehouseStrategy(filter(2));
        FefoStrategy fefo = new FefoStrategy(filter(2));

        StrategyRegistry registry = new StrategyRegistry(List.of(nearest, fefo));

        assertThat(registry.get(StrategyType.NEAREST)).isSameAs(nearest);
        assertThat(registry.get(StrategyType.FEFO)).isSameAs(fefo);
    }

    @Test
    void duplicateType_failsAtStartup() {
        assertThatThrownBy(() -> new StrategyRegistry(List.of(new NearestWarehouseStrategy(filter(2)), new FefoStrategy(filter(2)), new NearestWarehouseStrategy(filter(2)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate")
                .hasMessageContaining("NEAREST");
    }

    @Test
    void missingType_failsAtStartup() {
        assertThatThrownBy(() -> new StrategyRegistry(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AllocationStrategy implementation for NEAREST");
    }

    @Test
    void onlyOneOfTwoTypes_failsAtStartup() {
        assertThatThrownBy(() -> new StrategyRegistry(List.of(new NearestWarehouseStrategy(filter(2)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No AllocationStrategy implementation for FEFO");
    }
}
