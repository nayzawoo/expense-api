package com.nayzawoo.expense.adjustment;

import com.nayzawoo.expense.account.IdempotencyService;
import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.identity.FinancialAccess;
import com.nayzawoo.expense.identity.FinancialAccess.LockedHouseholdActor;
import com.nayzawoo.expense.shared.Money;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
class AdjustmentService {

    static final String CREATE_OPERATION =
            "POST /api/v1/households/{householdId}/accounts/{accountId}/reconciliations";
    static final String VOID_OPERATION =
            "POST /api/v1/households/{householdId}/adjustments/{adjustmentId}/void";

    private final FinancialAccess financialAccess;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    AdjustmentService(
            FinancialAccess financialAccess, IdempotencyService idempotency, JdbcTemplate jdbc, Clock clock) {
        this.financialAccess = financialAccess;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    List<AdjustmentView> list(ExpenseUser actor, UUID householdId) {
        LockedHouseholdActor locked = requireAdmin(actor, householdId, false);
        return jdbc.query(
                """
                SELECT t.id, t.kind, t.created_by_user_id, t.version, t.current_revision_no,
                       r.state, r.effective_date, r.amount, r.currency_code, r.change_reason, r.recorded_at,
                       r.balance_before, r.observed_balance, r.observed_balance_version,
                       r.account_id, a.name AS account_name,
                       u.display_name AS creator_display_name
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                JOIN account a
                  ON a.household_id = t.household_id
                 AND a.id = r.account_id
                JOIN app_user u
                  ON u.household_id = t.household_id
                 AND u.id = t.created_by_user_id
                WHERE t.household_id = ?
                  AND t.kind = 'ADJUSTMENT'
                  AND r.state = 'POSTED'
                ORDER BY r.effective_date DESC, t.id DESC
                """,
                (rs, rowNum) -> toView(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("state"),
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency_code"),
                        rs.getString("change_reason"),
                        rs.getBigDecimal("balance_before"),
                        rs.getBigDecimal("observed_balance"),
                        rs.getLong("observed_balance_version"),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_name"),
                        rs.getObject("created_by_user_id", UUID.class),
                        rs.getString("creator_display_name"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getLong("version"),
                        rs.getInt("current_revision_no"),
                        locked.admin()),
                locked.householdId());
    }

    AdjustmentView get(ExpenseUser actor, UUID householdId, UUID adjustmentId) {
        LockedHouseholdActor locked = requireAdmin(actor, householdId, false);
        AdjustmentView view = loadView(locked, adjustmentId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return view;
    }

    @Transactional
    MutationResult reconcile(
            ExpenseUser actor,
            UUID householdId,
            UUID accountId,
            String idempotencyKey,
            String actualBalanceRaw,
            Long expectedBalanceVersion,
            String reason) {
        LockedHouseholdActor locked = requireAdmin(actor, householdId, true);
        if (accountId == null || actualBalanceRaw == null || expectedBalanceVersion == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String normalizedReason = normalizeReason(reason);
        BigDecimal observedBalance = Money.parseSignedOpening(actualBalanceRaw);
        String requestHash = IdempotencyService.hashRequest(IdempotencyService.canonical(
                householdId.toString(),
                accountId.toString(),
                Money.format(observedBalance),
                Long.toString(expectedBalanceVersion),
                normalizedReason));
        var existing = idempotency.begin(
                locked.householdId(), locked.userId(), CREATE_OPERATION, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            IdempotencyService.Replay replay = existing.get();
            return new MutationResult(true, replay.httpStatus(), replay.resourceId(), replay.responseBody());
        }
        LocalDate today = householdToday(locked.timezone());
        AccountRow account = lockAccount(locked.householdId(), accountId);
        if (!"ACTIVE".equals(account.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNT_NOT_ACTIVE");
        }
        if (account.balanceVersion() != expectedBalanceVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "BALANCE_CHANGED");
        }
        BigDecimal balanceBefore = balanceAsOf(locked.householdId(), account.id(), today);
        BigDecimal amount = observedBalance.subtract(balanceBefore);
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "ZERO_DELTA");
        }
        if (amount.abs().compareTo(Money.MAX_ABS) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "ADJUSTMENT_OUT_OF_RANGE");
        }
        Instant now = Instant.now(clock);
        UUID transactionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO financial_transaction (
                    id, household_id, kind, created_by_user_id, current_revision_no, version,
                    opening_account_id, created_at)
                VALUES (?, ?, 'ADJUSTMENT', ?, 1, 0, NULL, ?)
                """,
                transactionId,
                locked.householdId(),
                locked.userId(),
                Timestamp.from(now));
        jdbc.update(
                """
                INSERT INTO transaction_revision (
                    household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                    effective_date, amount, currency_code, account_id, source_account_id,
                    destination_account_id, category_id, beneficiary_id, note, change_reason,
                    recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot,
                    category_name_snapshot, beneficiary_name_snapshot,
                    balance_before, observed_balance, observed_balance_version)
                VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?,
                        ?, ?, ?, ?, NULL, NULL, ?, ?, ?)
                """,
                locked.householdId(),
                transactionId,
                Date.valueOf(today),
                amount,
                locked.currency(),
                account.id(),
                normalizedReason,
                locked.userId(),
                Timestamp.from(now),
                account.name(),
                actor.getDisplayName(),
                balanceBefore,
                observedBalance,
                expectedBalanceVersion);
        jdbc.update(
                """
                INSERT INTO account_posting (
                    id, household_id, transaction_id, revision_no, account_id, effective_date,
                    currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, 1, 'APPLY', NULL, ?)
                """,
                UUID.randomUUID(),
                locked.householdId(),
                transactionId,
                account.id(),
                Date.valueOf(today),
                locked.currency(),
                amount,
                Timestamp.from(now));
        bumpBalanceVersion(locked.householdId(), now, account.id());
        AdjustmentView view = new AdjustmentView(
                transactionId,
                "ADJUSTMENT",
                "POSTED",
                today,
                Money.format(amount),
                locked.currency(),
                normalizedReason,
                Money.format(balanceBefore),
                Money.format(observedBalance),
                expectedBalanceVersion,
                new RefSummary(account.id(), account.name()),
                new ActorSummary(locked.userId(), actor.getDisplayName()),
                now,
                0L,
                1,
                true);
        String body = writeJson(view);
        idempotency.complete(
                locked.householdId(),
                locked.userId(),
                CREATE_OPERATION,
                idempotencyKey,
                201,
                transactionId,
                view.version(),
                body);
        return new MutationResult(false, 201, transactionId, body);
    }

    @Transactional
    MutationResult voidAdjustment(
            ExpenseUser actor,
            UUID householdId,
            UUID adjustmentId,
            String idempotencyKey,
            String ifMatch,
            String reason) {
        LockedHouseholdActor locked = requireAdmin(actor, householdId, true);
        long expectedVersion = requireIfMatch(ifMatch);
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String normalizedReason = normalizeReason(reason);
        String requestHash = IdempotencyService.hashRequest(IdempotencyService.canonical(
                householdId.toString(),
                adjustmentId.toString(),
                Long.toString(expectedVersion),
                normalizedReason));
        var existing = idempotency.begin(
                locked.householdId(), locked.userId(), VOID_OPERATION, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            IdempotencyService.Replay replay = existing.get();
            return new MutationResult(true, replay.httpStatus(), replay.resourceId(), replay.responseBody());
        }
        HeadRow head = lockHead(locked.householdId(), adjustmentId);
        if (!"POSTED".equals(head.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (head.version() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH");
        }
        AccountRow account = requireAccountForMutation(locked.householdId(), head.accountId(), head.accountId());
        Instant now = Instant.now(clock);
        int newRevision = head.revisionNo() + 1;
        jdbc.update(
                """
                INSERT INTO transaction_revision (
                    household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                    effective_date, amount, currency_code, account_id, source_account_id,
                    destination_account_id, category_id, beneficiary_id, note, change_reason,
                    recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot,
                    category_name_snapshot, beneficiary_name_snapshot,
                    balance_before, observed_balance, observed_balance_version)
                VALUES (?, ?, ?, 'VOID', 'VOID', ?, ?, ?, ?, ?, NULL, NULL, NULL, NULL, NULL, ?,
                        ?, ?, ?, ?, NULL, NULL, ?, ?, ?)
                """,
                locked.householdId(),
                adjustmentId,
                newRevision,
                head.revisionNo(),
                Date.valueOf(head.effectiveDate()),
                head.amount(),
                locked.currency(),
                account.id(),
                normalizedReason,
                locked.userId(),
                Timestamp.from(now),
                account.name(),
                actor.getDisplayName(),
                head.balanceBefore(),
                head.observedBalance(),
                head.observedBalanceVersion());
        reverseApplyLegs(locked.householdId(), adjustmentId, head.revisionNo(), newRevision, now);
        long newVersion = head.version() + 1;
        int updated = jdbc.update(
                """
                UPDATE financial_transaction
                SET current_revision_no = ?, version = ?
                WHERE household_id = ?
                  AND id = ?
                  AND version = ?
                """,
                newRevision,
                newVersion,
                locked.householdId(),
                adjustmentId,
                head.version());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH");
        }
        bumpBalanceVersion(locked.householdId(), now, account.id());
        AdjustmentView view = loadView(locked, adjustmentId);
        String body = writeJson(view);
        idempotency.complete(
                locked.householdId(),
                locked.userId(),
                VOID_OPERATION,
                idempotencyKey,
                200,
                adjustmentId,
                view.version(),
                body);
        return new MutationResult(false, 200, adjustmentId, body);
    }

    private LockedHouseholdActor requireAdmin(ExpenseUser actor, UUID householdId, boolean forWrite) {
        LockedHouseholdActor locked = forWrite
                ? financialAccess.lockForFinancialWrite(actor, householdId)
                : financialAccess.requireHouseholdMember(actor, householdId);
        if (!locked.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return locked;
    }

    private AdjustmentView loadView(LockedHouseholdActor locked, UUID adjustmentId) {
        List<AdjustmentView> rows = jdbc.query(
                """
                SELECT t.id, t.kind, t.created_by_user_id, t.version, t.current_revision_no,
                       r.state, r.effective_date, r.amount, r.currency_code, r.change_reason, r.recorded_at,
                       r.balance_before, r.observed_balance, r.observed_balance_version,
                       r.account_id, a.name AS account_name,
                       u.display_name AS creator_display_name
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                JOIN account a
                  ON a.household_id = t.household_id
                 AND a.id = r.account_id
                JOIN app_user u
                  ON u.household_id = t.household_id
                 AND u.id = t.created_by_user_id
                WHERE t.household_id = ?
                  AND t.id = ?
                  AND t.kind = 'ADJUSTMENT'
                """,
                (rs, rowNum) -> toView(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("state"),
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency_code"),
                        rs.getString("change_reason"),
                        rs.getBigDecimal("balance_before"),
                        rs.getBigDecimal("observed_balance"),
                        rs.getLong("observed_balance_version"),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_name"),
                        rs.getObject("created_by_user_id", UUID.class),
                        rs.getString("creator_display_name"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getLong("version"),
                        rs.getInt("current_revision_no"),
                        locked.admin()),
                locked.householdId(),
                adjustmentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private AdjustmentView toView(
            UUID id,
            String kind,
            String state,
            LocalDate effectiveDate,
            BigDecimal amount,
            String currency,
            String reason,
            BigDecimal balanceBefore,
            BigDecimal observedBalance,
            long observedBalanceVersion,
            UUID accountId,
            String accountName,
            UUID createdByUserId,
            String creatorDisplayName,
            Instant recordedAt,
            long version,
            int revisionNo,
            boolean admin) {
        boolean canVoid = admin && "POSTED".equals(state);
        return new AdjustmentView(
                id,
                kind,
                state,
                effectiveDate,
                Money.format(amount),
                currency,
                reason,
                Money.format(balanceBefore),
                Money.format(observedBalance),
                observedBalanceVersion,
                new RefSummary(accountId, accountName),
                new ActorSummary(createdByUserId, creatorDisplayName),
                recordedAt,
                version,
                revisionNo,
                canVoid);
    }

    private HeadRow lockHead(UUID householdId, UUID adjustmentId) {
        HeadRow head = jdbc.query(
                """
                SELECT t.id, t.created_by_user_id, t.version, t.current_revision_no,
                       r.state, r.effective_date, r.amount, r.change_reason,
                       r.balance_before, r.observed_balance, r.observed_balance_version, r.account_id
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                WHERE t.household_id = ?
                  AND t.id = ?
                  AND t.kind = 'ADJUSTMENT'
                FOR UPDATE OF t
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new HeadRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("created_by_user_id", UUID.class),
                            rs.getLong("version"),
                            rs.getInt("current_revision_no"),
                            rs.getString("state"),
                            rs.getDate("effective_date").toLocalDate(),
                            rs.getBigDecimal("amount"),
                            rs.getString("change_reason"),
                            rs.getBigDecimal("balance_before"),
                            rs.getBigDecimal("observed_balance"),
                            rs.getLong("observed_balance_version"),
                            rs.getObject("account_id", UUID.class));
                },
                householdId,
                adjustmentId);
        if (head == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return head;
    }

    private BigDecimal balanceAsOf(UUID householdId, UUID accountId, LocalDate asOf) {
        BigDecimal balance = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(signed_amount), 0)
                FROM account_posting
                WHERE household_id = ?
                  AND account_id = ?
                  AND effective_date <= ?
                """,
                BigDecimal.class,
                householdId,
                accountId,
                Date.valueOf(asOf));
        return balance == null ? BigDecimal.ZERO : balance;
    }

    private void reverseApplyLegs(
            UUID householdId, UUID transactionId, int fromRevision, int toRevision, Instant recordedAt) {
        List<PostingRow> applyLegs = jdbc.query(
                """
                SELECT id, account_id, effective_date, currency_code, signed_amount, leg_no
                FROM account_posting
                WHERE household_id = ?
                  AND transaction_id = ?
                  AND revision_no = ?
                  AND role = 'APPLY'
                ORDER BY leg_no ASC, id ASC
                """,
                (rs, rowNum) -> new PostingRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getString("currency_code"),
                        rs.getBigDecimal("signed_amount"),
                        rs.getInt("leg_no")),
                householdId,
                transactionId,
                fromRevision);
        if (applyLegs.size() != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        for (PostingRow leg : applyLegs) {
            jdbc.update(
                    """
                    INSERT INTO account_posting (
                        id, household_id, transaction_id, revision_no, account_id, effective_date,
                        currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REVERSE', ?, ?)
                    """,
                    UUID.randomUUID(),
                    householdId,
                    transactionId,
                    toRevision,
                    leg.accountId(),
                    Date.valueOf(leg.effectiveDate()),
                    leg.currencyCode(),
                    leg.signedAmount().negate(),
                    leg.legNo(),
                    leg.id(),
                    Timestamp.from(recordedAt));
        }
    }

    private void bumpBalanceVersion(UUID householdId, Instant now, UUID accountId) {
        int bumped = jdbc.update(
                """
                UPDATE account
                SET balance_version = balance_version + 1, updated_at = ?
                WHERE household_id = ?
                  AND id = ?
                """,
                Timestamp.from(now),
                householdId,
                accountId);
        if (bumped != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private AccountRow lockAccount(UUID householdId, UUID accountId) {
        AccountRow account = jdbc.query(
                """
                SELECT id, name, status, opened_on, balance_version
                FROM account
                WHERE household_id = ?
                  AND id = ?
                FOR UPDATE
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new AccountRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            rs.getString("status"),
                            rs.getDate("opened_on").toLocalDate(),
                            rs.getLong("balance_version"));
                },
                householdId,
                accountId);
        if (account == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return account;
    }

    private AccountRow requireAccountForMutation(UUID householdId, UUID accountId, UUID retainedId) {
        AccountRow account = lockAccount(householdId, accountId);
        if ("ACTIVE".equals(account.status()) || accountId.equals(retainedId)) {
            return account;
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNT_NOT_ACTIVE");
    }

    private static long requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED);
        }
        String raw = ifMatch.trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH");
        }
    }

    private LocalDate householdToday(String timezone) {
        return LocalDate.now(clock.withZone(ZoneId.of(timezone)));
    }

    private static String normalizeReason(String reason) {
        String trimmed = reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 500 || containsLineBreak(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private String writeJson(AdjustmentView view) {
        try {
            return json.writeValueAsString(toResponse(view));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    static AdjustmentResponse toResponse(AdjustmentView view) {
        return new AdjustmentResponse(
                view.id().toString(),
                view.kind(),
                view.state(),
                view.effectiveDate().toString(),
                view.amount(),
                view.currency(),
                view.reason(),
                view.balanceBefore(),
                view.observedBalance(),
                view.observedBalanceVersion(),
                new RefResponse(view.account().id().toString(), view.account().name()),
                new ActorResponse(view.createdBy().userId().toString(), view.createdBy().displayName()),
                view.recordedAt().toString(),
                view.version(),
                view.revisionNo(),
                new CapabilitiesResponse(view.canVoid()));
    }

    record AdjustmentView(
            UUID id,
            String kind,
            String state,
            LocalDate effectiveDate,
            String amount,
            String currency,
            String reason,
            String balanceBefore,
            String observedBalance,
            long observedBalanceVersion,
            RefSummary account,
            ActorSummary createdBy,
            Instant recordedAt,
            long version,
            int revisionNo,
            boolean canVoid) {}

    record RefSummary(UUID id, String name) {}

    record ActorSummary(UUID userId, String displayName) {}

    record MutationResult(boolean replayed, int httpStatus, UUID resourceId, String responseBody) {}

    record AdjustmentResponse(
            String id,
            String kind,
            String state,
            String effectiveDate,
            String amount,
            String currency,
            String reason,
            String balanceBefore,
            String observedBalance,
            long observedBalanceVersion,
            RefResponse account,
            ActorResponse createdBy,
            String recordedAt,
            long version,
            int revisionNo,
            CapabilitiesResponse capabilities) {}

    record RefResponse(String id, String name) {}

    record ActorResponse(String userId, String displayName) {}

    record CapabilitiesResponse(boolean canVoid) {}

    private record AccountRow(UUID id, String name, String status, LocalDate openedOn, long balanceVersion) {}

    private record HeadRow(
            UUID id,
            UUID createdByUserId,
            long version,
            int revisionNo,
            String state,
            LocalDate effectiveDate,
            BigDecimal amount,
            String reason,
            BigDecimal balanceBefore,
            BigDecimal observedBalance,
            long observedBalanceVersion,
            UUID accountId) {}

    private record PostingRow(
            UUID id,
            UUID accountId,
            LocalDate effectiveDate,
            String currencyCode,
            BigDecimal signedAmount,
            int legNo) {}
}
