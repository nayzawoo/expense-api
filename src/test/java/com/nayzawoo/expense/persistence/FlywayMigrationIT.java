package com.nayzawoo.expense.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

class FlywayMigrationIT {

    @Test
    void cleanMigrationIsRepeatableAndValidates() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6")) {
            postgres.start();
            Flyway flyway = flywayFor(postgres);

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(7);
            assertThat(successfulVersionOneCount(postgres)).isEqualTo(1);

            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(successfulVersionOneCount(postgres)).isEqualTo(1);
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        }
    }

    @Test
    void rejectsMigrationChecksumMismatchInDisposableDatabase() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6")) {
            postgres.start();
            Flyway flyway = flywayFor(postgres);

            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(7);
            assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    Statement statement = connection.createStatement()) {
                int changed = statement.executeUpdate("""
                        UPDATE flyway_schema_history
                        SET checksum = CASE WHEN checksum <> 0 THEN 0 ELSE 1 END
                        WHERE version = '1' AND success
                        """);
                assertThat(changed).isEqualTo(1);
            }

            assertThat(flyway.validateWithResult().validationSuccessful).isFalse();
            assertThatThrownBy(flyway::validate)
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("checksum");
        }
    }

    private static Flyway flywayFor(PostgreSQLContainer postgres) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load();
    }

    private static int successfulVersionOneCount(PostgreSQLContainer postgres) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT count(*)
                        FROM flyway_schema_history
                        WHERE version = '1' AND success
                        """)) {
            assertThat(rows.next()).isTrue();
            return rows.getInt(1);
        }
    }
}
