package com.chamani.order_allocation_engine.fulfilment.strategy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HaversineTest {

    /** Reference distances are the commonly published great-circle distances; the tolerance is 1%. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "London-Paris,        51.5074, -0.1278,   48.8566,   2.3522,   344",
            "NewYork-London,      40.7128, -74.0060,  51.5074,  -0.1278,  5570",
            "Sydney-Melbourne,   -33.8688, 151.2093, -37.8136, 144.9631,   714",
            "Colombo-Kandy,        6.9271,  79.8612,   7.2906,  80.6337,    94",
            "Colombo-Galle,        6.9271,  79.8612,   6.0535,  80.2210,   105"
    })
    void knownCityDistances_areWithinOnePercent(String pair, double lat1, double lon1, double lat2, double lon2, double expectedKm) {
        double km = Haversine.distanceKm(lat1, lon1, lat2, lon2);
        assertThat(km).as(pair).isCloseTo(expectedKm, within(expectedKm * 0.01));
    }

    @Test
    void samePoint_isZero() {
        assertThat(Haversine.distanceKm(6.9271, 79.8612, 6.9271, 79.8612)).isZero();
    }

    @Test
    void isSymmetric() {
        assertThat(Haversine.distanceKm(6.9271, 79.8612, 7.2906, 80.6337))
                .isEqualTo(Haversine.distanceKm(7.2906, 80.6337, 6.9271, 79.8612));
    }

    @Test
    void antipodes_areHalfTheEarthsCircumference() {
        assertThat(Haversine.distanceKm(0, 0, 0, 180)).isCloseTo(20015, within(20015 * 0.01));
    }

    @Test
    void crossingTheDateLine_takesTheShortWay() {
        assertThat(Haversine.distanceKm(0, 179.5, 0, -179.5)).isCloseTo(111.2, within(1.2));
    }
}
