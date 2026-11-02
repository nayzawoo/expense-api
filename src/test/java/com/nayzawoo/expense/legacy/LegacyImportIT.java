package com.nayzawoo.expense.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LegacyImportIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    private JdbcTemplate jdbc;
    private final Instant importedAt = Instant.parse("2026-10-23T10:00:00Z");

    @BeforeEach
    void prepare() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("TRUNCATE household CASCADE");
    }

    @Test
    void importsAllKindsWithValidLedgerGroupsBalancesSnapshotsAndWorkingLaravelPasswords() throws Exception {
        var plan = LegacyImportPlanTest.plan(LegacyImportFixture.bytes());
        assertThat(write(plan, true)).isEqualTo(LegacyImportWriter.Result.IMPORTED);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class)).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_posting", Long.class)).isEqualTo(11L);
        assertThat(balance(plan.accounts.get("1").id)).isEqualByComparingTo("1170");
        assertThat(balance(plan.accounts.get("2").id)).isEqualByComparingTo("400");
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction_revision WHERE transaction_id = ?", BigDecimal.class,
                plan.id("openings", "1"))).isEqualByComparingTo("1170");
        assertThat(jdbc.queryForObject("SELECT balance_before FROM transaction_revision WHERE transaction_id = ?", BigDecimal.class,
                plan.id("account_adjustments", "1"))).isEqualByComparingTo("1200");
        assertThat(jdbc.queryForObject("SELECT account_name_snapshot FROM transaction_revision WHERE transaction_id = ?", String.class,
                plan.id("expenses", "1"))).isEqualTo("Old Cash");
        assertThat(jdbc.queryForObject("SELECT details->>'originalOpening' FROM audit_event WHERE event_type = 'LARAVEL_ACCOUNT_IMPORTED' AND resource_id = ?",
                String.class, plan.accounts.get("1").id)).isEqualTo("1000");
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user", String.class);
        assertThat(new BCryptPasswordEncoder().matches(LegacyImportFixture.PASSWORD, hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT must_change_password FROM app_user", Boolean.class)).isFalse();
    }

    @Test
    void checkDatabaseExercisesDeferredConstraintsThenRollsBackEverything() throws Exception {
        var plan = LegacyImportPlanTest.plan(LegacyImportFixture.bytes());
        assertThat(write(plan, false)).isEqualTo(LegacyImportWriter.Result.VALIDATED_AND_ROLLED_BACK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM household", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_posting", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class)).isZero();
        assertThat(write(plan, true)).isEqualTo(LegacyImportWriter.Result.IMPORTED);
    }

    @Test
    void sameSnapshotIsANoOpEvenAfterOrdinaryUseAndDifferentSnapshotsCannotMerge() throws Exception {
        var plan = LegacyImportPlanTest.plan(LegacyImportFixture.bytes());
        write(plan, true);
        jdbc.update("UPDATE account SET name = 'Renamed after import' WHERE id = ?", plan.accounts.get("1").id);
        assertThat(write(plan, true)).isEqualTo(LegacyImportWriter.Result.ALREADY_IMPORTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class)).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT name FROM account WHERE id = ?", String.class, plan.accounts.get("1").id))
                .isEqualTo("Renamed after import");
        var changed = LegacyImportPlanTest.plan(LegacyImportFixture.replace("#FF8800", "#AA8800"));
        assertThatThrownBy(() -> write(changed, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("separate empty database");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class)).isEqualTo(9L);
    }

    @Test
    void lateWriteFailureRollsBackUsersAccountsLedgerAndAuditTogether() {
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT test_reject_import CHECK (event_type <> 'LARAVEL_IMPORT_COMPLETED')");
        try {
            var plan = LegacyImportPlanTest.plan(LegacyImportFixture.bytes());
            assertThatThrownBy(() -> write(plan, true)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("test_reject_import");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM household", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM account_posting", Long.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT test_reject_import");
        }
    }

    @Test
    void bootstrapReplacementRollsBackDuringValidationAndCommitsWithTheImport() throws Exception {
        seedBootstrap();
        var plan = LegacyImportPlanTest.plan(LegacyImportFixture.bytes());
        assertThat(writeReplacingBootstrap(plan, false)).isEqualTo(LegacyImportWriter.Result.VALIDATED_AND_ROLLED_BACK);
        assertThat(jdbc.queryForObject("SELECT email FROM app_user", String.class)).isEqualTo("bootstrap@example.com");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class)).isZero();
        assertThat(writeReplacingBootstrap(plan, true)).isEqualTo(LegacyImportWriter.Result.IMPORTED);
        assertThat(jdbc.queryForObject("SELECT email FROM app_user", String.class)).isEqualTo("admin@example.com");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class)).isEqualTo(9L);
    }

    @Test
    void bootstrapReplacementRefusesFinancialDataAndPreservesTheExistingHousehold() throws Exception {
        var plan = LegacyImportPlanTest.plan(LegacyImportFixture.bytes());
        write(plan, true);
        var changed = LegacyImportPlanTest.plan(LegacyImportFixture.replace("#FF8800", "#AA8800"));
        assertThatThrownBy(() -> writeReplacingBootstrap(changed, true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Bootstrap replacement refused");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class)).isEqualTo(9L);
    }

    @Test
    void failedImportRestoresReplacedBootstrapData() {
        seedBootstrap();
        jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT test_reject_import CHECK (event_type <> 'LARAVEL_IMPORT_COMPLETED')");
        try {
            var plan = LegacyImportPlanTest.plan(LegacyImportFixture.bytes());
            assertThatThrownBy(() -> writeReplacingBootstrap(plan, true))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .hasMessageContaining("test_reject_import");
            assertThat(jdbc.queryForObject("SELECT email FROM app_user", String.class)).isEqualTo("bootstrap@example.com");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM beneficiary", Long.class)).isEqualTo(2L);
        } finally {
            jdbc.execute("ALTER TABLE audit_event DROP CONSTRAINT test_reject_import");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LEGACY_IMPORT_TEST_FILE", matches = ".+")
    void validatesProvidedSnapshotInAnIsolatedDatabaseWithoutCommitting() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(System.getenv("LEGACY_IMPORT_TEST_FILE")));
        var plan = LegacyImportPlan.read(bytes, ZoneId.of("UTC"), LocalDate.now(ZoneId.of("Asia/Yangon")));
        assertThat(write(plan, false)).isEqualTo(LegacyImportWriter.Result.VALIDATED_AND_ROLLED_BACK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM household", Long.class)).isZero();
    }

    private LegacyImportWriter.Result write(LegacyImportPlan plan, boolean apply) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            return new LegacyImportWriter().write(connection, plan, apply, importedAt);
        }
    }

    @Test
    void resetsFinancialHistoryAndBalancesWhileRetainingMetadataAndValidZeroOpenings() throws Exception {
        write(LegacyImportPlanTest.plan(LegacyImportFixture.bytes()), true);
        jdbc.update("UPDATE account SET status = 'ARCHIVED' WHERE name = 'Wallet'");
        jdbc.update("""
                INSERT INTO idempotency_record (id, household_id, actor_user_id, operation, key,
                    request_hash, http_status, response_body, created_at, expires_at)
                SELECT gen_random_uuid(), household_id, id, 'POST /api/v1/households/{householdId}/incomes',
                    'old-income', repeat('a', 64), 201, '{}', now(), now() + interval '1 day'
                FROM app_user
                """);
        var accounts = jdbc.queryForList("SELECT to_jsonb(a) - 'version' - 'balance_version' - 'updated_at' AS data FROM account a ORDER BY id");
        var versions = jdbc.queryForList("SELECT id, version + 1 AS version, balance_version + 1 AS balance_version FROM account ORDER BY id");
        var retained = new java.util.LinkedHashMap<String, java.util.List<java.util.Map<String, Object>>>();
        for (String table : java.util.List.of("household", "app_user", "category", "beneficiary", "audit_event", "spring_session", "spring_session_attributes")) {
            retained.put(table, jdbc.queryForList("SELECT * FROM " + table));
        }

        resetData();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction WHERE kind <> 'OPENING'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_posting", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_revision WHERE amount <> 0", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_revision", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT to_jsonb(a) - 'version' - 'balance_version' - 'updated_at' AS data FROM account a ORDER BY id"))
                .isEqualTo(accounts);
        assertThat(jdbc.queryForList("SELECT id, version, balance_version FROM account ORDER BY id")).isEqualTo(versions);
        retained.forEach((table, rows) -> assertThat(jdbc.queryForList("SELECT * FROM " + table)).containsExactlyInAnyOrderElementsOf(rows));
        // Exercise the deferred opening validator against the rebuilt ledger, including archived accounts.
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER test_validate_reset_openings AFTER UPDATE ON account
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_account_opening()
                """);
        try {
            jdbc.update("UPDATE account SET version = version");
        } finally {
            jdbc.execute("DROP TRIGGER test_validate_reset_openings ON account");
        }
        resetData();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction_revision", Long.class)).isEqualTo(2L);
    }

    @Test
    void resetFailureRollsBackFinancialHistoryAndAccountChanges() throws Exception {
        write(LegacyImportPlanTest.plan(LegacyImportFixture.bytes()), true);
        var postings = jdbc.queryForList("SELECT * FROM account_posting ORDER BY id");
        var revisions = jdbc.queryForList("SELECT * FROM transaction_revision ORDER BY transaction_id, revision_no");
        var accounts = jdbc.queryForList("SELECT * FROM account ORDER BY id");
        jdbc.execute("ALTER TABLE account ADD CONSTRAINT test_reject_reset CHECK (balance_version < 0) NOT VALID");
        try {
            assertThatThrownBy(this::resetData).hasMessageContaining("test_reject_reset");
            assertThat(jdbc.queryForList("SELECT * FROM account_posting ORDER BY id")).isEqualTo(postings);
            assertThat(jdbc.queryForList("SELECT * FROM transaction_revision ORDER BY transaction_id, revision_no")).isEqualTo(revisions);
            assertThat(jdbc.queryForList("SELECT * FROM account ORDER BY id")).isEqualTo(accounts);
        } finally {
            jdbc.execute("ALTER TABLE account DROP CONSTRAINT test_reject_reset");
        }
    }

    @Test
    void resetsAnEmptyDatabase() throws Exception {
        resetData();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class)).isZero();
    }

    private void resetData() throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute(Files.readString(Path.of("scripts/reset-data.sql")));
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private LegacyImportWriter.Result writeReplacingBootstrap(LegacyImportPlan plan, boolean apply) throws Exception {
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            return new LegacyImportWriter().write(connection, plan, apply, importedAt, true);
        }
    }

    private void seedBootstrap() {
        var householdId = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO household VALUES (?, 'Bootstrap', 'MMK', 0, 'Asia/Yangon', true)", householdId);
        jdbc.update("""
                INSERT INTO app_user (id, household_id, email, display_name, password_hash, role, status,
                    must_change_password, security_version)
                VALUES (?, ?, 'bootstrap@example.com', 'Bootstrap Admin', ?, 'ADMIN', 'ACTIVE', true, 0)
                """, java.util.UUID.randomUUID(), householdId, LegacyImportFixture.HASH);
        for (String name : java.util.List.of("Household", "Other")) {
            jdbc.update("""
                    INSERT INTO beneficiary (id, household_id, name, kind, sort_order, status, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 0, 'ACTIVE', 0, now(), now())
                    """, java.util.UUID.randomUUID(), householdId, name, name.equals("Household") ? "HOUSEHOLD" : "OTHER");
        }
    }

    private BigDecimal balance(java.util.UUID accountId) {
        return jdbc.queryForObject("SELECT SUM(signed_amount) FROM account_posting WHERE account_id = ?", BigDecimal.class, accountId);
    }
}
