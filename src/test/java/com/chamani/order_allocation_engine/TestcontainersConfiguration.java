package com.chamani.order_allocation_engine;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Throwaway Postgres for the integration tests; @ServiceConnection wires the datasource to it.
 * The container is a static singleton so every cached Spring context shares one instance
 * instead of starting its own.
 * Every distinct test configuration is a cached Spring context with its own connection pool, and they all share this
 * one server, so max_connections is raised well above the default of 100.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18")
            .withCommand("postgres", "-c", "max_connections=400");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return POSTGRES;
    }
}
