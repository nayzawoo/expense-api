package com.nayzawoo.expense.account;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class OpeningWriteService {

    private final JdbcTemplate jdbc;

    OpeningWriteService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void createOpening(
            UUID householdId,
            UUID accountId,
            UUID actorUserId,
            String actorDisplayName,
            String accountName,
            LocalDate openedOn,
            BigDecimal openingAmount,
            String currencyCode,
            Instant recordedAt) {
        UUID transactionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO financial_transaction (
                    id, household_id, kind, created_by_user_id, current_revision_no, version,
                    opening_account_id, created_at)
                VALUES (?, ?, 'OPENING', ?, 1, 0, ?, ?)
                """,
                transactionId,
                householdId,
                actorUserId,
                accountId,
                Timestamp.from(recordedAt));
        jdbc.update(
                """
                INSERT INTO transaction_revision (
                    household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                    effective_date, amount, currency_code, account_id, source_account_id,
                    destination_account_id, category_id, beneficiary_id, note, change_reason,
                    recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot)
                VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, NULL,
                        ?, ?, ?, ?)
                """,
                householdId,
                transactionId,
                java.sql.Date.valueOf(openedOn),
                openingAmount,
                currencyCode,
                accountId,
                actorUserId,
                Timestamp.from(recordedAt),
                accountName,
                actorDisplayName);
        if (openingAmount.compareTo(BigDecimal.ZERO) != 0) {
            jdbc.update(
                    """
                    INSERT INTO account_posting (
                        id, household_id, transaction_id, revision_no, account_id, effective_date,
                        currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                    VALUES (?, ?, ?, 1, ?, ?, ?, ?, 1, 'APPLY', NULL, ?)
                    """,
                    UUID.randomUUID(),
                    householdId,
                    transactionId,
                    accountId,
                    java.sql.Date.valueOf(openedOn),
                    currencyCode,
                    openingAmount,
                    Timestamp.from(recordedAt));
        }
    }
}
