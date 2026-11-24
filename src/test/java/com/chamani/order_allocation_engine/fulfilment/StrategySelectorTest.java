package com.chamani.order_allocation_engine.fulfilment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StrategySelectorTest {

    @Mock StrategyProperties properties;

    StrategySelector selector;

    @BeforeEach
    void setUp() {
        when(properties.defaultFor(CustomerTier.PRIORITY)).thenReturn(StrategyType.NEAREST);
        when(properties.defaultFor(CustomerTier.STANDARD)).thenReturn(StrategyType.FEFO);
        selector = new StrategySelector(properties);
    }

    @Test
    void explicitRequestWins_overTheTierDefault() {
        assertThat(selector.select(StrategyType.SPLIT, CustomerTier.PRIORITY)).isEqualTo(StrategyType.SPLIT);
        assertThat(selector.select(StrategyType.NEAREST, CustomerTier.STANDARD)).isEqualTo(StrategyType.NEAREST);
        verifyNoInteractions(properties);
    }

    @Test
    void withoutARequest_theTierDefaultApplies() {
        assertThat(selector.select(null, CustomerTier.PRIORITY)).isEqualTo(StrategyType.NEAREST);
        assertThat(selector.select(null, CustomerTier.STANDARD)).isEqualTo(StrategyType.FEFO);
        verify(properties).defaultFor(CustomerTier.PRIORITY);
        verify(properties).defaultFor(CustomerTier.STANDARD);
    }

    @Test
    void parse_acceptsAnyCase_andNullMeansNotGiven() {
        assertThat(selector.parse("FEFO")).isEqualTo(StrategyType.FEFO);
        assertThat(selector.parse(" split ")).isEqualTo(StrategyType.SPLIT);
        assertThat(selector.parse(null)).isNull();
    }

    @Test
    void parse_unknownOrBlankStrategy_isRejected() {
        assertThatThrownBy(() -> selector.parse("CHEAPEST"))
                .isInstanceOf(InvalidStrategyException.class)
                .hasMessageContaining("CHEAPEST").hasMessageContaining("NEAREST").hasMessageContaining("FEFO").hasMessageContaining("SPLIT");
        assertThatThrownBy(() -> selector.parse("")).isInstanceOf(InvalidStrategyException.class);
    }
}
