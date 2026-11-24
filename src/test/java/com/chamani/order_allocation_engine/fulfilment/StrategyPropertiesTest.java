package com.chamani.order_allocation_engine.fulfilment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** allocation.strategy.tier-defaults is validated when the application starts. */
class StrategyPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(StrategyProperties.class)
    static class Config {
    }

    @Test
    void bothTiers_bind() {
        runner.withPropertyValues("allocation.strategy.tier-defaults.PRIORITY=NEAREST", "allocation.strategy.tier-defaults.STANDARD=FEFO")
                .run(context -> {
                    StrategyProperties properties = context.getBean(StrategyProperties.class);
                    assertThat(properties.defaultFor(CustomerTier.PRIORITY)).isEqualTo(StrategyType.NEAREST);
                    assertThat(properties.defaultFor(CustomerTier.STANDARD)).isEqualTo(StrategyType.FEFO);
                });
    }

    @Test
    void unknownStrategyName_failsStartup() {
        runner.withPropertyValues("allocation.strategy.tier-defaults.PRIORITY=CHEAPEST", "allocation.strategy.tier-defaults.STANDARD=FEFO")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingTier_failsStartup() {
        runner.withPropertyValues("allocation.strategy.tier-defaults.PRIORITY=NEAREST")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "allocation.strategy.tier-defaults.STANDARD is required (one of [NEAREST, FEFO, SPLIT])");
                });
    }

    @Test
    void noPropertiesAtAll_failsStartup() {
        runner.run(context -> assertThat(context).hasFailed());
    }
}
