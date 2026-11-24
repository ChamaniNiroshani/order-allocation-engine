package com.chamani.order_allocation_engine.fulfilment;

import com.chamani.order_allocation_engine.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dev seed (db/seed) must apply on top of the real migrations, and the default configuration must NOT load it.
 * The seed runs in its own schema so it never leaves demo rows in the database the other tests share.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DevSeedDataTest {

    @Autowired DataSource dataSource;
    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;

    @Test
    void defaultProfile_loadsNoSeedData() {
        assertThat(env.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE description LIKE '%dev seed%'", Integer.class))
                .isZero();
    }

    @Test
    void seed_appliesOnTopOfTheMigrations_andIsRepeatable() throws SQLException {
        String url;
        String user;
        String password;
        try (Connection c = dataSource.getConnection()) {
            url = c.getMetaData().getURL();
            user = c.getMetaData().getUserName();
        }
        password = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getPassword();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);

        new JdbcTemplate(ds).execute("DROP SCHEMA IF EXISTS seed_check CASCADE");
        Flyway flyway = Flyway.configure().dataSource(ds).schemas("seed_check").createSchemas(true)
                .locations("classpath:db/migration", "classpath:db/seed").load();
        flyway.migrate();
        JdbcTemplate seeded = new JdbcTemplate(ds);

        assertThat(seeded.queryForList("SELECT name FROM seed_check.warehouse ORDER BY name", String.class))
                .containsExactly("Colombo", "Galle", "Kandy");
        assertThat(seeded.queryForObject("SELECT count(*) FROM seed_check.inventory_batch WHERE sku = 'MILK-1L'", Integer.class))
                .isGreaterThanOrEqualTo(4);
        assertThat(seeded.queryForObject("SELECT count(*) FROM seed_check.inventory_batch WHERE expiry_date IS NULL", Integer.class))
                .isPositive();
        assertThat(seeded.queryForObject("SELECT count(*) FROM seed_check.inventory_batch WHERE expiry_date < CURRENT_DATE", Integer.class))
                .isPositive();

        // running it again changes nothing
        flyway.migrate();
        assertThat(seeded.queryForObject("SELECT count(*) FROM seed_check.warehouse", Integer.class)).isEqualTo(3);

        seeded.execute("DROP SCHEMA seed_check CASCADE");
    }
}
