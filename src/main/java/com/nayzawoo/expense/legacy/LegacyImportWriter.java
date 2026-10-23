package com.nayzawoo.expense.legacy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Writes a complete snapshot atomically, using the same ledger constraints as ordinary financial writes. */
final class LegacyImportWriter {
    private final JsonMapper json = JsonMapper.builder().build();

    Result write(Connection connection, LegacyImportPlan plan, boolean apply, Instant importedAt) throws SQLException {
        return write(connection, plan, apply, importedAt, false);
    }

    Result write(Connection connection, LegacyImportPlan plan, boolean apply, Instant importedAt,
            boolean replaceBootstrap) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new IllegalArgumentException("Importer requires a dedicated connection");
        }
        connection.setAutoCommit(false);
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        try {
            jdbc.execute("SET LOCAL lock_timeout = '5s'");
            jdbc.execute("LOCK TABLE household IN EXCLUSIVE MODE");
            if (replaceBootstrap) {
                jdbc.execute("""
                        LOCK TABLE app_user, account, category, beneficiary, financial_transaction,
                            transaction_revision, account_posting, audit_event, spring_session,
                            spring_session_attributes, idempotency_record IN EXCLUSIVE MODE
                        """);
            }
            Long previous = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM audit_event
                    WHERE event_type = 'LARAVEL_IMPORT_COMPLETED'
                      AND details->>'checksum' = ? AND details->>'sourceTimezone' = ?
                      AND details->>'mappingVersion' = '1'
                    """, Long.class, plan.checksum, plan.sourceTimezone.getId());
            if (previous != null && previous > 0) {
                connection.rollback();
                return Result.ALREADY_IMPORTED;
            }
            Long households = jdbc.queryForObject("SELECT count(*) FROM household", Long.class);
            if (replaceBootstrap && households != null && households == 1) {
                replaceBootstrap(jdbc);
                households = 0L;
            }
            if (households == null || households != 0) {
                throw new IllegalArgumentException(
                        "Target already has a household. Use a separate empty database; existing data is never overwritten.");
            }
            jdbc.update("""
                    INSERT INTO household (id, name, currency, currency_scale, timezone, singleton)
                    VALUES (?, 'Imported household', 'MMK', 0, 'Asia/Yangon', true)
                    """, plan.householdId);
            for (var user : plan.users) {
                jdbc.update("""
                        INSERT INTO app_user (id, household_id, email, display_name, password_hash, role,
                            status, must_change_password, password_changed_at, security_version)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', false, ?, 0)
                        """, user.id(), plan.householdId, user.email(), user.name(), user.hash(), user.role(),
                        Timestamp.from(importedAt));
            }
            for (var category : plan.categories) {
                jdbc.update("""
                        INSERT INTO category (id, household_id, name, color, icon_key, sort_order,
                            status, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?)
                        """, category.id(), plan.householdId, category.name(), category.color(), category.icon(),
                        category.sortOrder(), Timestamp.from(category.createdAt()), Timestamp.from(category.updatedAt()));
            }
            int order = 0;
            for (var beneficiary : plan.beneficiaries.values()) {
                jdbc.update("""
                        INSERT INTO beneficiary (id, household_id, name, kind, sort_order, status, version,
                            created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?)
                        """, beneficiary.id(), plan.householdId, beneficiary.name(), beneficiary.kind(), order++,
                        Timestamp.from(importedAt), Timestamp.from(importedAt));
            }
            var openingActor = plan.users.stream().filter(user -> user.role().equals("ADMIN")).findFirst().orElseThrow();
            for (var account : plan.accounts.values()) {
                jdbc.update("""
                        INSERT INTO account (id, household_id, name, owner_label, type, currency_code, opened_on,
                            status, sort_order, version, balance_version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, 'MMK', ?, ?, ?, 0, ?, ?, ?)
                        """, account.id, plan.householdId, account.name, account.owner, account.type,
                        Date.valueOf(account.openedOn), account.status, account.sortOrder, account.postingCount,
                        Timestamp.from(account.createdAt), Timestamp.from(account.updatedAt));
                UUID openingId = plan.id("openings", account.sourceId);
                insertHead(jdbc, plan.householdId, openingId, "OPENING", openingActor.id(), account.id, importedAt);
                jdbc.update("""
                        INSERT INTO transaction_revision (household_id, transaction_id, revision_no, operation, state,
                            effective_date, amount, currency_code, account_id, recorded_by_user_id, recorded_at,
                            account_name_snapshot, actor_display_name_snapshot, note)
                        VALUES (?, ?, 1, 'CREATE', 'POSTED', ?, ?, 'MMK', ?, ?, ?, ?, ?, ?)
                        """, plan.householdId, openingId, Date.valueOf(account.openedOn), account.opening, account.id,
                        openingActor.id(), Timestamp.from(importedAt), account.name, openingActor.name(),
                        "Rebased from Laravel opening " + account.originalDate + ": " + account.originalOpening);
                if (account.opening.signum() != 0) {
                    insertPosting(jdbc, plan.householdId, openingId, 1, account.id, account.openedOn,
                            account.opening, importedAt);
                }
                audit(jdbc, plan.householdId, openingActor.id(), "LARAVEL_ACCOUNT_IMPORTED", "ACCOUNT", account.id,
                        importedAt, Map.of("source", plan.source, "sourceId", account.sourceId,
                                "originalDate", account.originalDate.toString(), "originalOpening", account.originalOpening.toPlainString(),
                                "openedOn", account.openedOn.toString(), "opening", account.opening.toPlainString(),
                                "preOpeningNet", account.preOpeningNet.toPlainString()));
            }
            Map<UUID, Long> balanceVersions = new HashMap<>();
            for (var entry : plan.entries) {
                insertHead(jdbc, plan.householdId, entry.id(), entry.kind(), entry.actor().id(), null, entry.recordedAt());
                String reason = entry.kind().equals("ADJUSTMENT")
                        ? (entry.note() == null || entry.note().isBlank() ? "Imported Laravel adjustment" : entry.note()) : null;
                jdbc.update("""
                        INSERT INTO transaction_revision (household_id, transaction_id, revision_no, operation, state,
                            effective_date, amount, currency_code, account_id, source_account_id, destination_account_id,
                            category_id, beneficiary_id, note, change_reason, recorded_by_user_id, recorded_at,
                            account_name_snapshot, actor_display_name_snapshot, category_name_snapshot,
                            beneficiary_name_snapshot, balance_before, observed_balance, observed_balance_version)
                        VALUES (?, ?, 1, 'CREATE', 'POSTED', ?, ?, 'MMK', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, plan.householdId, entry.id(), Date.valueOf(entry.date()), entry.amount(),
                        entry.account() == null ? null : entry.account().id,
                        entry.from() == null ? null : entry.from().id,
                        entry.to() == null ? null : entry.to().id,
                        entry.category() == null ? null : entry.category().id(),
                        entry.beneficiary() == null ? null : entry.beneficiary().id(),
                        entry.note(), reason, entry.actor().id(), Timestamp.from(entry.recordedAt()), entry.accountName(),
                        entry.actor().name(), entry.category() == null ? null : entry.category().name(),
                        entry.beneficiary() == null ? null : entry.beneficiary().name(),
                        entry.balanceBefore(), entry.actualBalance(), entry.kind().equals("ADJUSTMENT")
                                ? balanceVersions.getOrDefault(entry.account().id, 0L) : null);
                int legNo = 1;
                for (var leg : entry.legs()) {
                    insertPosting(jdbc, plan.householdId, entry.id(), legNo++, leg.account().id,
                            entry.date(), leg.amount(), entry.recordedAt());
                    balanceVersions.merge(leg.account().id, 1L, Long::sum);
                }
            }
            verify(jdbc, plan);
            audit(jdbc, plan.householdId, openingActor.id(), "LARAVEL_IMPORT_COMPLETED", "HOUSEHOLD", plan.householdId,
                    importedAt, Map.of("source", plan.source, "checksum", plan.checksum,
                            "sourceTimezone", plan.sourceTimezone.getId(), "mappingVersion", 1,
                            "transactions", plan.entries.size(), "preOpeningEntries", plan.preOpeningEntries));
            // Dry validation must exercise the deferred ledger triggers before rolling back.
            jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE");
            if (apply) {
                connection.commit();
                return Result.IMPORTED;
            }
            connection.rollback();
            return Result.VALIDATED_AND_ROLLED_BACK;
        } catch (RuntimeException | SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private void replaceBootstrap(JdbcTemplate jdbc) {
        Boolean bootstrapOnly = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM app_user) = 1
                  AND (SELECT count(*) FROM app_user WHERE role = 'ADMIN') = 1
                  AND NOT EXISTS (SELECT 1 FROM account)
                  AND NOT EXISTS (SELECT 1 FROM category)
                  AND NOT EXISTS (SELECT 1 FROM financial_transaction)
                  AND NOT EXISTS (SELECT 1 FROM transaction_revision)
                  AND NOT EXISTS (SELECT 1 FROM account_posting)
                  AND NOT EXISTS (SELECT 1 FROM idempotency_record)
                  AND (SELECT count(*) FROM beneficiary) = 2
                  AND (SELECT count(*) FROM beneficiary WHERE
                        (name = 'Household' AND kind = 'HOUSEHOLD') OR (name = 'Other' AND kind = 'OTHER')) = 2
                """, Boolean.class);
        if (!Boolean.TRUE.equals(bootstrapOnly)) {
            throw new IllegalArgumentException(
                    "Bootstrap replacement refused: target must contain only one admin and the two default beneficiaries, with no financial data.");
        }
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM beneficiary");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM household");
    }

    private void insertHead(JdbcTemplate jdbc, UUID householdId, UUID id, String kind, UUID actorId,
            UUID openingAccountId, Instant recordedAt) {
        jdbc.update("""
                INSERT INTO financial_transaction (id, household_id, kind, created_by_user_id, current_revision_no,
                    version, opening_account_id, created_at) VALUES (?, ?, ?, ?, 1, 0, ?, ?)
                """, id, householdId, kind, actorId, openingAccountId, Timestamp.from(recordedAt));
    }

    private void insertPosting(JdbcTemplate jdbc, UUID householdId, UUID transactionId, int legNo,
            UUID accountId, LocalDate date, BigDecimal amount, Instant recordedAt) {
        UUID id = UUID.nameUUIDFromBytes((transactionId + ":leg:" + legNo).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO account_posting (id, household_id, transaction_id, revision_no, account_id, effective_date,
                    currency_code, signed_amount, leg_no, role, recorded_at)
                VALUES (?, ?, ?, 1, ?, ?, 'MMK', ?, ?, 'APPLY', ?)
                """, id, householdId, transactionId, accountId, Date.valueOf(date), amount, legNo, Timestamp.from(recordedAt));
    }

    private void verify(JdbcTemplate jdbc, LegacyImportPlan plan) {
        for (var account : plan.accounts.values()) {
            BigDecimal actual = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(signed_amount), 0) FROM account_posting WHERE account_id = ?",
                    BigDecimal.class, account.id);
            // Compute the source balance independently of the rebased opening calculation.
            BigDecimal expected = account.originalOpening;
            for (var entry : plan.entries) {
                if (!entry.date().isBefore(account.originalDate)) {
                    for (var leg : entry.legs()) {
                        if (leg.account() == account) {
                            expected = expected.add(leg.amount());
                        }
                    }
                }
            }
            if (actual == null || actual.compareTo(expected) != 0) {
                throw new IllegalStateException("Balance mismatch for source account " + account.sourceId);
            }
        }
        Long transactionCount = jdbc.queryForObject("SELECT count(*) FROM financial_transaction", Long.class);
        if (transactionCount == null || transactionCount != plan.entries.size() + plan.accounts.size()) {
            throw new IllegalStateException("Transaction count mismatch");
        }
        Map<String, BigDecimal> expectedTotals = new HashMap<>();
        for (var entry : plan.entries) {
            if (entry.kind().equals("EXPENSE") || entry.kind().equals("INCOME")) {
                expectedTotals.merge(entry.kind() + ":" + YearMonth.from(entry.date()), entry.amount(), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> actualTotals = new HashMap<>();
        jdbc.query("""
                SELECT t.kind, to_char(r.effective_date, 'YYYY-MM') AS month, SUM(r.amount) AS total
                FROM financial_transaction t JOIN transaction_revision r
                  ON r.household_id = t.household_id AND r.transaction_id = t.id AND r.revision_no = 1
                WHERE t.kind IN ('EXPENSE', 'INCOME') GROUP BY t.kind, month
                """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> actualTotals.put(
                        rs.getString("kind") + ":" + rs.getString("month"), rs.getBigDecimal("total")));
        if (!expectedTotals.equals(actualTotals)) {
            throw new IllegalStateException("Monthly income/expense total mismatch");
        }
    }

    private void audit(JdbcTemplate jdbc, UUID householdId, UUID actorId, String event, String resource,
            UUID resourceId, Instant recordedAt, Map<String, ?> details) {
        jdbc.update("""
                INSERT INTO audit_event (id, household_id, actor_user_id, event_type, resource_type, resource_id,
                    recorded_at, details) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, UUID.randomUUID(), householdId, actorId, event, resource, resourceId,
                Timestamp.from(recordedAt), json.writeValueAsString(details));
    }

    enum Result { IMPORTED, ALREADY_IMPORTED, VALIDATED_AND_ROLLED_BACK }
}
