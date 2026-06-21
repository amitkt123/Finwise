package org.amit.finwise.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Validates that all Flyway migrations apply cleanly to a fresh Postgres instance.
 *
 * Requires Docker. Run explicitly with:
 *   ./mvnw test -Dtest=FlywayMigrationTest -Dflyway.migration.test=true
 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finwise_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void migrationsApplyCleanlyToFreshDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertThatCode(flyway::migrate)
                .as("All Flyway migrations should apply without error")
                .doesNotThrowAnyException();
    }

    @Test
    void migrationsPassValidateAfterMigrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();

        flyway.migrate();

        assertThatCode(flyway::validate)
                .as("Schema should pass Flyway validation after migrate")
                .doesNotThrowAnyException();
    }
}
