package com.nayzawoo.expense.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LedgerOpeningMigrationIT {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @BeforeEach
    void migrateAndClear() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
        JdbcTemplate jdbc = jdbc();
        jdbc.update(
                """
                TRUNCATE account_posting, transaction_revision, financial_transaction, account,
                         app_user, household
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    void rejectsAccountWithoutOpeningAtCommit() {
        JdbcTemplate jdbc = jdbc();
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        seedHousehold(jdbc, householdId, userId);

        assertThatThrownBy(() -> jdbc.execute((Connection connection) -> {
                    connection.setAutoCommit(false);
                    try (var statement = connection.prepareStatement(
                            """
                            INSERT INTO account (
                                id, household_id, name, owner_label, type, currency_code, opened_on,
                                status, sort_order, version, balance_version, created_at, updated_at)
                            VALUES (?, ?, 'Cash', 'Ada', 'CASH', 'MMK', DATE '2026-01-10',
                                    'ACTIVE', 1, 0, 1, ?, ?)
                            """)) {
                        statement.setObject(1, accountId);
                        statement.setObject(2, householdId);
                        Timestamp now = Timestamp.from(Instant.parse("2026-01-15T00:00:00Z"));
                        statement.setTimestamp(3, now);
                        statement.setTimestamp(4, now);
                        statement.executeUpdate();
                        connection.commit();
                    } catch (Exception ex) {
                        connection.rollback();
                        throw ex;
                    }
                    return null;
                }))
                .hasMessageContaining("exactly one opening");
        assertThat(jdbc.queryForObject("select count(*) from account", Long.class)).isZero();
    }

    @Test
    void acceptsAtomicAccountAndOpening() {
        JdbcTemplate jdbc = jdbc();
        UUID householdId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        seedHousehold(jdbc, householdId, userId);
        Timestamp now = Timestamp.from(Instant.parse("2026-01-15T00:00:00Z"));

        jdbc.execute((Connection connection) -> {
            connection.setAutoCommit(false);
            try {
                try (var account = connection.prepareStatement(
                        """
                        INSERT INTO account (
                            id, household_id, name, owner_label, type, currency_code, opened_on,
                            status, sort_order, version, balance_version, created_at, updated_at)
                        VALUES (?, ?, 'Cash', 'Ada', 'CASH', 'MMK', DATE '2026-01-10',
                                'ACTIVE', 1, 0, 1, ?, ?)
                        """)) {
                    account.setObject(1, accountId);
                    account.setObject(2, householdId);
                    account.setTimestamp(3, now);
                    account.setTimestamp(4, now);
                    account.executeUpdate();
                }
                try (var head = connection.prepareStatement(
                        """
                        INSERT INTO financial_transaction (
                            id, household_id, kind, created_by_user_id, current_revision_no, version,
                            opening_account_id, created_at)
                        VALUES (?, ?, 'OPENING', ?, 1, 0, ?, ?)
                        """)) {
                    head.setObject(1, transactionId);
                    head.setObject(2, householdId);
                    head.setObject(3, userId);
                    head.setObject(4, accountId);
                    head.setTimestamp(5, now);
                    head.executeUpdate();
                }
                try (var revision = connection.prepareStatement(
                        """
                        INSERT INTO transaction_revision (
                            household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                            effective_date, amount, currency_code, account_id, recorded_by_user_id, recorded_at,
                            account_name_snapshot, actor_display_name_snapshot)
                        VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, DATE '2026-01-10', ?, 'MMK', ?, ?, ?, 'Cash', 'Ada')
                        """)) {
                    revision.setObject(1, householdId);
                    revision.setObject(2, transactionId);
                    revision.setBigDecimal(3, new BigDecimal("1000"));
                    revision.setObject(4, accountId);
                    revision.setObject(5, userId);
                    revision.setTimestamp(6, now);
                    revision.executeUpdate();
                }
                try (var posting = connection.prepareStatement(
                        """
                        INSERT INTO account_posting (
                            id, household_id, transaction_id, revision_no, account_id, effective_date,
                            currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                        VALUES (?, ?, ?, 1, ?, DATE '2026-01-10', 'MMK', ?, 1, 'APPLY', NULL, ?)
                        """)) {
                    posting.setObject(1, UUID.randomUUID());
                    posting.setObject(2, householdId);
                    posting.setObject(3, transactionId);
                    posting.setObject(4, accountId);
                    posting.setBigDecimal(5, new BigDecimal("1000"));
                    posting.setTimestamp(6, now);
                    posting.executeUpdate();
                }
                connection.commit();
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            }
            return null;
        });

        assertThat(jdbc.queryForObject("select count(*) from account", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select coalesce(sum(signed_amount),0) from account_posting where account_id = ?",
                        BigDecimal.class,
                        accountId))
                .isEqualByComparingTo("1000");
    }

    private static void seedHousehold(JdbcTemplate jdbc, UUID householdId, UUID userId) {
        jdbc.update(
                """
                INSERT INTO household (id, name, currency, currency_scale, timezone, singleton)
                VALUES (?, 'Home', 'MMK', 0, 'Asia/Yangon', true)
                """,
                householdId);
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, household_id, email, display_name, password_hash, role, status,
                    must_change_password, security_version)
                VALUES (?, ?, 'admin@example.com', 'Ada', 'hash', 'ADMIN', 'ACTIVE', false, 0)
                """,
                userId,
                householdId);
    }

    private static JdbcTemplate jdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return new JdbcTemplate(dataSource);
    }
}
