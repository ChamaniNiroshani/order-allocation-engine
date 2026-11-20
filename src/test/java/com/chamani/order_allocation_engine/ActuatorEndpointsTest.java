package com.chamani.order_allocation_engine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpointGroup;
import org.springframework.boot.actuate.health.HealthEndpointGroups;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The operational endpoints Prometheus and the platform rely on. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability   // tests switch metrics export off by default; this turns the Prometheus registry back on
@Import(TestcontainersConfiguration.class)
class ActuatorEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HealthEndpointGroups healthGroups;

    @Test
    void prometheusEndpoint_returnsMetricsInTextFormat() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("hikaricp_connections_active")))
                .andExpect(content().string(containsString("application=\"order-allocation-engine\"")));
    }

    @Test
    void readinessProbe_isUp_andIncludesTheDatabase() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        // health details are hidden from callers by design, so check the group's membership instead
        HealthEndpointGroup readiness = healthGroups.get("readiness");
        assertTrue(readiness.isMember("db"), "readiness must include the database");
        assertTrue(readiness.isMember("readinessState"));
        assertFalse(healthGroups.get("liveness").isMember("db"), "liveness must not depend on the database");
    }

    @Test
    void livenessProbe_isUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void infoEndpoint_reportsAppNameAndBuild() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("order-allocation-engine"))
                .andExpect(jsonPath("$.build.version").exists());
    }

    @Test
    void unlistedEndpoints_areNotExposed() throws Exception {
        mockMvc.perform(get("/actuator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.health").exists())
                .andExpect(jsonPath("$._links.info").exists())
                .andExpect(jsonPath("$._links.metrics").exists())
                .andExpect(jsonPath("$._links.prometheus").exists())
                .andExpect(jsonPath("$._links.env").doesNotExist())
                .andExpect(jsonPath("$._links.beans").doesNotExist());
    }
}
