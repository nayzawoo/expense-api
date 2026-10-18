package com.nayzawoo.expense.expense;

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
class ExpenseService {

    static final String CREATE_OPERATION = "POST /api/v1/households/{householdId}/expenses";

    private final FinancialAccess financialAccess;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    ExpenseService(
            FinancialAccess financialAccess, IdempotencyService idempotency, JdbcTemplate jdbc, Clock clock) {
        this.financialAccess = financialAccess;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    List<ExpenseView> list(ExpenseUser actor, UUID householdId) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        return jdbc.query(
                """
                SELECT t.id, t.kind, t.created_by_user_id, t.version, t.current_revision_no,
                       r.state, r.effective_date, r.amount, r.currency_code, r.note, r.recorded_at,
                       r.account_id, a.name AS account_name,
                       r.category_id, c.name AS category_name,
                       r.beneficiary_id, b.name AS beneficiary_name, b.kind AS beneficiary_kind,
                       u.display_name AS creator_display_name
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                JOIN account a
                  ON a.household_id = t.household_id
                 AND a.id = r.account_id
                JOIN category c
                  ON c.household_id = t.household_id
                 AND c.id = r.category_id
                JOIN beneficiary b
                  ON b.household_id = t.household_id
                 AND b.id = r.beneficiary_id
                JOIN app_user u
                  ON u.household_id = t.household_id
                 AND u.id = t.created_by_user_id
                WHERE t.household_id = ?
                  AND t.kind = 'EXPENSE'
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
                        rs.getString("note"),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_name"),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("category_name"),
                        rs.getObject("beneficiary_id", UUID.class),
                        rs.getString("beneficiary_name"),
                        rs.getString("beneficiary_kind"),
                        rs.getObject("created_by_user_id", UUID.class),
                        rs.getString("creator_display_name"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getLong("version"),
                        rs.getInt("current_revision_no"),
                        locked.userId(),
                        locked.admin()),
                locked.householdId());
    }

    ExpenseView get(ExpenseUser actor, UUID householdId, UUID expenseId) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        ExpenseView view = loadView(locked, expenseId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return view;
    }

    @Transactional
    CreateResult create(
            ExpenseUser actor,
            UUID householdId,
            String idempotencyKey,
            LocalDate effectiveDate,
            String amountRaw,
            UUID accountId,
            UUID categoryId,
            UUID beneficiaryId,
            String note) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        if (effectiveDate == null
                || amountRaw == null
                || accountId == null
                || categoryId == null
                || beneficiaryId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        BigDecimal amount = Money.parsePositivePrincipal(amountRaw);
        String normalizedNote = normalizeNote(note);
        LocalDate today = householdToday(locked.timezone());
        if (effectiveDate.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "FUTURE_DATE");
        }
        AccountRow account = lockAccount(locked.householdId(), accountId);
        if (!"ACTIVE".equals(account.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNT_NOT_ACTIVE");
        }
        if (effectiveDate.isBefore(account.openedOn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "DATE_BEFORE_OPENING");
        }
        CategoryRow category = requireActiveCategory(locked.householdId(), categoryId);
        BeneficiaryRow beneficiary = requireActiveBeneficiary(locked.householdId(), beneficiaryId);
        String requestHash = IdempotencyService.hashRequest(IdempotencyService.canonical(
                householdId.toString(),
                effectiveDate.toString(),
                Money.format(amount),
                accountId.toString(),
                categoryId.toString(),
                beneficiaryId.toString(),
                normalizedNote == null ? "" : normalizedNote));
        var existing = idempotency.begin(
                locked.householdId(), locked.userId(), CREATE_OPERATION, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            IdempotencyService.Replay replay = existing.get();
            return new CreateResult(true, replay.httpStatus(), replay.resourceId(), replay.responseBody());
        }
        Instant now = Instant.now(clock);
        UUID transactionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO financial_transaction (
                    id, household_id, kind, created_by_user_id, current_revision_no, version,
                    opening_account_id, created_at)
                VALUES (?, ?, 'EXPENSE', ?, 1, 0, NULL, ?)
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
                    category_name_snapshot, beneficiary_name_snapshot)
                VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, NULL,
                        ?, ?, ?, ?, ?, ?)
                """,
                locked.householdId(),
                transactionId,
                Date.valueOf(effectiveDate),
                amount,
                locked.currency(),
                account.id(),
                category.id(),
                beneficiary.id(),
                normalizedNote,
                locked.userId(),
                Timestamp.from(now),
                account.name(),
                actor.getDisplayName(),
                category.name(),
                beneficiary.name());
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
                Date.valueOf(effectiveDate),
                locked.currency(),
                amount.negate(),
                Timestamp.from(now));
        int bumped = jdbc.update(
                """
                UPDATE account
                SET balance_version = balance_version + 1, updated_at = ?
                WHERE household_id = ?
                  AND id = ?
                """,
                Timestamp.from(now),
                locked.householdId(),
                account.id());
        if (bumped != 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        ExpenseView view = new ExpenseView(
                transactionId,
                "EXPENSE",
                "POSTED",
                effectiveDate,
                Money.format(amount),
                locked.currency(),
                normalizedNote,
                new RefSummary(account.id(), account.name(), null),
                new RefSummary(category.id(), category.name(), null),
                new RefSummary(beneficiary.id(), beneficiary.name(), beneficiary.kind()),
                new ActorSummary(locked.userId(), actor.getDisplayName()),
                now,
                0L,
                1,
                true,
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
        return new CreateResult(false, 201, transactionId, body);
    }

    private ExpenseView loadView(LockedHouseholdActor locked, UUID expenseId) {
        List<ExpenseView> rows = jdbc.query(
                """
                SELECT t.id, t.kind, t.created_by_user_id, t.version, t.current_revision_no,
                       r.state, r.effective_date, r.amount, r.currency_code, r.note, r.recorded_at,
                       r.account_id, a.name AS account_name,
                       r.category_id, c.name AS category_name,
                       r.beneficiary_id, b.name AS beneficiary_name, b.kind AS beneficiary_kind,
                       u.display_name AS creator_display_name
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                JOIN account a
                  ON a.household_id = t.household_id
                 AND a.id = r.account_id
                JOIN category c
                  ON c.household_id = t.household_id
                 AND c.id = r.category_id
                JOIN beneficiary b
                  ON b.household_id = t.household_id
                 AND b.id = r.beneficiary_id
                JOIN app_user u
                  ON u.household_id = t.household_id
                 AND u.id = t.created_by_user_id
                WHERE t.household_id = ?
                  AND t.id = ?
                  AND t.kind = 'EXPENSE'
                """,
                (rs, rowNum) -> toView(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("state"),
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency_code"),
                        rs.getString("note"),
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_name"),
                        rs.getObject("category_id", UUID.class),
                        rs.getString("category_name"),
                        rs.getObject("beneficiary_id", UUID.class),
                        rs.getString("beneficiary_name"),
                        rs.getString("beneficiary_kind"),
                        rs.getObject("created_by_user_id", UUID.class),
                        rs.getString("creator_display_name"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getLong("version"),
                        rs.getInt("current_revision_no"),
                        locked.userId(),
                        locked.admin()),
                locked.householdId(),
                expenseId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ExpenseView toView(
            UUID id,
            String kind,
            String state,
            LocalDate effectiveDate,
            BigDecimal amount,
            String currency,
            String note,
            UUID accountId,
            String accountName,
            UUID categoryId,
            String categoryName,
            UUID beneficiaryId,
            String beneficiaryName,
            String beneficiaryKind,
            UUID createdByUserId,
            String creatorDisplayName,
            Instant recordedAt,
            long version,
            int revisionNo,
            UUID actorUserId,
            boolean admin) {
        boolean ownerOrAdmin = admin || actorUserId.equals(createdByUserId);
        return new ExpenseView(
                id,
                kind,
                state,
                effectiveDate,
                Money.format(amount),
                currency,
                note,
                new RefSummary(accountId, accountName, null),
                new RefSummary(categoryId, categoryName, null),
                new RefSummary(beneficiaryId, beneficiaryName, beneficiaryKind),
                new ActorSummary(createdByUserId, creatorDisplayName),
                recordedAt,
                version,
                revisionNo,
                ownerOrAdmin && "POSTED".equals(state),
                ownerOrAdmin && "POSTED".equals(state));
    }

    private AccountRow lockAccount(UUID householdId, UUID accountId) {
        AccountRow account = jdbc.query(
                """
                SELECT id, name, status, opened_on
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
                            rs.getDate("opened_on").toLocalDate());
                },
                householdId,
                accountId);
        if (account == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return account;
    }

    private CategoryRow requireActiveCategory(UUID householdId, UUID categoryId) {
        CategoryRow category = jdbc.query(
                """
                SELECT id, name, status
                FROM category
                WHERE household_id = ?
                  AND id = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new CategoryRow(
                            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"));
                },
                householdId,
                categoryId);
        if (category == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!"ACTIVE".equals(category.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "CATEGORY_NOT_ACTIVE");
        }
        return category;
    }

    private BeneficiaryRow requireActiveBeneficiary(UUID householdId, UUID beneficiaryId) {
        BeneficiaryRow beneficiary = jdbc.query(
                """
                SELECT id, name, kind, status
                FROM beneficiary
                WHERE household_id = ?
                  AND id = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new BeneficiaryRow(
                            rs.getObject("id", UUID.class),
                            rs.getString("name"),
                            rs.getString("kind"),
                            rs.getString("status"));
                },
                householdId,
                beneficiaryId);
        if (beneficiary == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!"ACTIVE".equals(beneficiary.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "BENEFICIARY_NOT_ACTIVE");
        }
        return beneficiary;
    }

    private LocalDate householdToday(String timezone) {
        return LocalDate.now(clock.withZone(ZoneId.of(timezone)));
    }

    private static String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 2000 || containsLineBreak(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return trimmed;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private String writeJson(ExpenseView view) {
        try {
            return json.writeValueAsString(toResponse(view));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    static ExpenseResponse toResponse(ExpenseView view) {
        return new ExpenseResponse(
                view.id().toString(),
                view.kind(),
                view.state(),
                view.effectiveDate().toString(),
                view.amount(),
                view.currency(),
                view.note(),
                new RefResponse(view.account().id().toString(), view.account().name(), null),
                new RefResponse(view.category().id().toString(), view.category().name(), null),
                new RefResponse(
                        view.beneficiary().id().toString(),
                        view.beneficiary().name(),
                        view.beneficiary().kind()),
                new ActorResponse(view.createdBy().userId().toString(), view.createdBy().displayName()),
                view.recordedAt().toString(),
                view.version(),
                view.revisionNo(),
                new CapabilitiesResponse(view.canReplace(), view.canVoid()));
    }

    record ExpenseView(
            UUID id,
            String kind,
            String state,
            LocalDate effectiveDate,
            String amount,
            String currency,
            String note,
            RefSummary account,
            RefSummary category,
            RefSummary beneficiary,
            ActorSummary createdBy,
            Instant recordedAt,
            long version,
            int revisionNo,
            boolean canReplace,
            boolean canVoid) {}

    record RefSummary(UUID id, String name, String kind) {}

    record ActorSummary(UUID userId, String displayName) {}

    record CreateResult(boolean replayed, int httpStatus, UUID resourceId, String responseBody) {}

    record ExpenseResponse(
            String id,
            String kind,
            String state,
            String effectiveDate,
            String amount,
            String currency,
            String note,
            RefResponse account,
            RefResponse category,
            RefResponse beneficiary,
            ActorResponse createdBy,
            String recordedAt,
            long version,
            int revisionNo,
            CapabilitiesResponse capabilities) {}

    record RefResponse(String id, String name, String kind) {}

    record ActorResponse(String userId, String displayName) {}

    record CapabilitiesResponse(boolean canReplace, boolean canVoid) {}

    private record AccountRow(UUID id, String name, String status, LocalDate openedOn) {}

    private record CategoryRow(UUID id, String name, String status) {}

    private record BeneficiaryRow(UUID id, String name, String kind, String status) {}
}
