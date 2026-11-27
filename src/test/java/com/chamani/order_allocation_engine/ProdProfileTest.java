package com.chamani.order_allocation_engine;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the public instance exposes and how it is sized. Observability is switched on for the test, because Spring Boot
 * turns metrics export off in tests, which would hide /actuator/prometheus whatever the profile said.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("prod")
class ProdProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void readinessIsUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());     // no details for anonymous callers
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/prometheus", "/actuator/metrics", "/actuator/info", "/actuator/env",
            "/actuator/heapdump"})
    void onlyHealthIsExposed(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    void thePoolIsSmall_andTheGaugeDoesNotQueryTheDatabase() throws SQLException {
        assertEquals(5, dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize());
        assertEquals("false", environment.getProperty("metrics.reservations.enabled"));
        assertEquals("false", environment.getProperty("management.otlp.tracing.export.enabled"));
    }
}
