package com.chamani.order_allocation_engine;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** V6 gives every key that was stored before it the flow that used it, so old keys still replay and are still scoped. */
@Testcontainers
class IdempotencyKeyBackfillTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    private static Flyway flywayUpTo(String version) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load();
    }

    @Test
    void existingKeys_getTheFlowThatStoredThem() {
        flywayUpTo("5").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        order(jdbc, "direct-ok", "ALLOCATED", null, false);
        order(jdbc, "direct-rejected", "FAILED", "Insufficient inventory: only 2 available", false);
        order(jdbc, "checkout-confirmed", "CONFIRMED", null, true);
        order(jdbc, "checkout-rejected", "FAILED", "Insufficient inventory", false);
        order(jdbc, "used-twice", "ALLOCATED", null, false);
        fulfilment(jdbc, "fulfilment-only");
        fulfilment(jdbc, "used-twice");

        flywayUpTo("6").migrate();

        Map<String, String> flows = jdbc.queryForList("SELECT idempotency_key, flow FROM idempotency_key").stream()
                .collect(Collectors.toMap(r -> (String) r.get("idempotency_key"), r -> (String) r.get("flow")));
        assertEquals(Map.of(
                "direct-ok", "ORDER",
                "direct-rejected", "ORDER",
                "checkout-confirmed", "CHECKOUT",
                "checkout-rejected", "CHECKOUT",
                "used-twice", "ORDER",
                "fulfilment-only", "FULFILMENT"), flows);
    }

    private static void order(JdbcTemplate jdbc, String key, String status, String reason, boolean reserved) {
        jdbc.update("INSERT INTO orders (order_id, item_id, quantity_requested, status, idempotency_key, failure_reason, reserved_at) "
                        + "VALUES (?, ?, 1, ?, ?, ?, CASE WHEN ? THEN now() END)",
                UUID.randomUUID(), UUID.randomUUID(), status, key, reason, reserved);
    }

    private static void fulfilment(JdbcTemplate jdbc, String key) {
        jdbc.update("INSERT INTO fulfilment_order (id, sku, quantity, delivery_lat, delivery_lon, customer_tier, strategy, "
                        + "status, idempotency_key, created_at) VALUES (?, 'S', 1, 0, 0, 'STANDARD', 'NEAREST', 'ALLOCATED', ?, now())",
                UUID.randomUUID(), key);
    }
}
