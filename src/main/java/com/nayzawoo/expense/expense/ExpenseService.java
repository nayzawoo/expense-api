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
    static final String REPLACE_OPERATION = "PUT /api/v1/households/{householdId}/expenses/{expenseId}";
    static final String VOID_OPERATION = "POST /api/v1/households/{householdId}/expenses/{expenseId}/void";

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


    @Transactional
    CreateResult replace(
            ExpenseUser actor,
            UUID householdId,
            UUID expenseId,
            String idempotencyKey,
            String ifMatch,
            LocalDate effectiveDate,
            String amountRaw,
            UUID accountId,
            UUID categoryId,
            UUID beneficiaryId,
            String note,
            String changeReason) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        long expectedVersion = requireIfMatch(ifMatch);
        if (effectiveDate == null
                || amountRaw == null
                || accountId == null
                || categoryId == null
                || beneficiaryId == null
                || changeReason == null
                || changeReason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        BigDecimal amount = Money.parsePositivePrincipal(amountRaw);
        String normalizedNote = normalizeNote(note);
        String normalizedReason = normalizeReason(changeReason);
        LocalDate today = householdToday(locked.timezone());
        if (effectiveDate.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "FUTURE_DATE");
        }
        String requestHash = IdempotencyService.hashRequest(IdempotencyService.canonical(
                householdId.toString(),
                expenseId.toString(),
                Long.toString(expectedVersion),
                effectiveDate.toString(),
                Money.format(amount),
                accountId.toString(),
                categoryId.toString(),
                beneficiaryId.toString(),
                normalizedNote == null ? "" : normalizedNote,
                normalizedReason));
        var existing = idempotency.begin(
                locked.householdId(), locked.userId(), REPLACE_OPERATION, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            IdempotencyService.Replay replay = existing.get();
            return new CreateResult(true, replay.httpStatus(), replay.resourceId(), replay.responseBody());
        }
        HeadRow head = lockHead(locked.householdId(), expenseId);
        requireOwnerOrAdmin(locked, head.createdByUserId());
        if (!"POSTED".equals(head.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (head.version() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH");
        }
        lockAccountsSorted(locked.householdId(), head.accountId(), accountId);
        AccountRow newAccount = requireAccountForMutation(
                locked.householdId(), accountId, head.accountId());
        if (effectiveDate.isBefore(newAccount.openedOn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "DATE_BEFORE_OPENING");
        }
        CategoryRow category = requireCategoryForMutation(
                locked.householdId(), categoryId, head.categoryId());
        BeneficiaryRow beneficiary = requireBeneficiaryForMutation(
                locked.householdId(), beneficiaryId, head.beneficiaryId());
        Instant now = Instant.now(clock);
        int newRevision = head.revisionNo() + 1;
        jdbc.update(
                """
                INSERT INTO transaction_revision (
                    household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                    effective_date, amount, currency_code, account_id, source_account_id,
                    destination_account_id, category_id, beneficiary_id, note, change_reason,
                    recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot,
                    category_name_snapshot, beneficiary_name_snapshot)
                VALUES (?, ?, ?, 'REPLACE', 'POSTED', ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?)
                """,
                locked.householdId(),
                expenseId,
                newRevision,
                head.revisionNo(),
                Date.valueOf(effectiveDate),
                amount,
                locked.currency(),
                newAccount.id(),
                category.id(),
                beneficiary.id(),
                normalizedNote,
                normalizedReason,
                locked.userId(),
                Timestamp.from(now),
                newAccount.name(),
                actor.getDisplayName(),
                category.name(),
                beneficiary.name());
        reverseApplyLegs(locked.householdId(), expenseId, head.revisionNo(), newRevision, now);
        jdbc.update(
                """
                INSERT INTO account_posting (
                    id, household_id, transaction_id, revision_no, account_id, effective_date,
                    currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 'APPLY', NULL, ?)
                """,
                UUID.randomUUID(),
                locked.householdId(),
                expenseId,
                newRevision,
                newAccount.id(),
                Date.valueOf(effectiveDate),
                locked.currency(),
                amount.negate(),
                Timestamp.from(now));
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
                expenseId,
                head.version());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH");
        }
        bumpBalanceVersions(locked.householdId(), now, head.accountId(), newAccount.id());
        ExpenseView view = loadView(locked, expenseId);
        String body = writeJson(view);
        idempotency.complete(
                locked.householdId(),
                locked.userId(),
                REPLACE_OPERATION,
                idempotencyKey,
                200,
                expenseId,
                view.version(),
                body);
        return new CreateResult(false, 200, expenseId, body);
    }

    @Transactional
    CreateResult voidExpense(
            ExpenseUser actor,
            UUID householdId,
            UUID expenseId,
            String idempotencyKey,
            String ifMatch,
            String reason) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        long expectedVersion = requireIfMatch(ifMatch);
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String normalizedReason = normalizeReason(reason);
        String requestHash = IdempotencyService.hashRequest(IdempotencyService.canonical(
                householdId.toString(),
                expenseId.toString(),
                Long.toString(expectedVersion),
                normalizedReason));
        var existing = idempotency.begin(
                locked.householdId(), locked.userId(), VOID_OPERATION, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            IdempotencyService.Replay replay = existing.get();
            return new CreateResult(true, replay.httpStatus(), replay.resourceId(), replay.responseBody());
        }
        HeadRow head = lockHead(locked.householdId(), expenseId);
        requireOwnerOrAdmin(locked, head.createdByUserId());
        if (!"POSTED".equals(head.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (head.version() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH");
        }
        lockAccountsSorted(locked.householdId(), head.accountId());
        AccountRow account = requireAccountForMutation(
                locked.householdId(), head.accountId(), head.accountId());
        CategoryRow category = requireCategoryForMutation(
                locked.householdId(), head.categoryId(), head.categoryId());
        BeneficiaryRow beneficiary = requireBeneficiaryForMutation(
                locked.householdId(), head.beneficiaryId(), head.beneficiaryId());
        Instant now = Instant.now(clock);
        int newRevision = head.revisionNo() + 1;
        jdbc.update(
                """
                INSERT INTO transaction_revision (
                    household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                    effective_date, amount, currency_code, account_id, source_account_id,
                    destination_account_id, category_id, beneficiary_id, note, change_reason,
                    recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot,
                    category_name_snapshot, beneficiary_name_snapshot)
                VALUES (?, ?, ?, 'VOID', 'VOID', ?, ?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?)
                """,
                locked.householdId(),
                expenseId,
                newRevision,
                head.revisionNo(),
                Date.valueOf(head.effectiveDate()),
                head.amount(),
                locked.currency(),
                account.id(),
                category.id(),
                beneficiary.id(),
                head.note(),
                normalizedReason,
                locked.userId(),
                Timestamp.from(now),
                account.name(),
                actor.getDisplayName(),
                category.name(),
                beneficiary.name());
        reverseApplyLegs(locked.householdId(), expenseId, head.revisionNo(), newRevision, now);
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
                expenseId,
                head.version());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH");
        }
        bumpBalanceVersions(locked.householdId(), now, head.accountId());
        ExpenseView view = loadView(locked, expenseId);
        String body = writeJson(view);
        idempotency.complete(
                locked.householdId(),
                locked.userId(),
                VOID_OPERATION,
                idempotencyKey,
                200,
                expenseId,
                view.version(),
                body);
        return new CreateResult(false, 200, expenseId, body);
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
        CategoryRow category = loadCategory(householdId, categoryId);
        if (!"ACTIVE".equals(category.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "CATEGORY_NOT_ACTIVE");
        }
        return category;
    }

    private BeneficiaryRow requireActiveBeneficiary(UUID householdId, UUID beneficiaryId) {
        BeneficiaryRow beneficiary = loadBeneficiary(householdId, beneficiaryId);
        if (!"ACTIVE".equals(beneficiary.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "BENEFICIARY_NOT_ACTIVE");
        }
        return beneficiary;
    }


    private HeadRow lockHead(UUID householdId, UUID expenseId) {
        HeadRow head = jdbc.query(
                """
                SELECT t.id, t.created_by_user_id, t.version, t.current_revision_no,
                       r.state, r.effective_date, r.amount, r.note,
                       r.account_id, r.category_id, r.beneficiary_id
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                WHERE t.household_id = ?
                  AND t.id = ?
                  AND t.kind = 'EXPENSE'
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
                            rs.getString("note"),
                            rs.getObject("account_id", UUID.class),
                            rs.getObject("category_id", UUID.class),
                            rs.getObject("beneficiary_id", UUID.class));
                },
                householdId,
                expenseId);
        if (head == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return head;
    }

    private void lockAccountsSorted(UUID householdId, UUID... accountIds) {
        java.util.TreeSet<UUID> ordered = new java.util.TreeSet<>();
        for (UUID accountId : accountIds) {
            if (accountId != null) {
                ordered.add(accountId);
            }
        }
        for (UUID accountId : ordered) {
            lockAccount(householdId, accountId);
        }
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
        if (applyLegs.isEmpty()) {
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

    private void bumpBalanceVersions(UUID householdId, Instant now, UUID... accountIds) {
        java.util.TreeSet<UUID> ordered = new java.util.TreeSet<>();
        for (UUID accountId : accountIds) {
            if (accountId != null) {
                ordered.add(accountId);
            }
        }
        for (UUID accountId : ordered) {
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
    }

    private AccountRow requireAccountForMutation(UUID householdId, UUID accountId, UUID retainedId) {
        AccountRow account = lockAccount(householdId, accountId);
        if ("ACTIVE".equals(account.status()) || accountId.equals(retainedId)) {
            return account;
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNT_NOT_ACTIVE");
    }

    private CategoryRow requireCategoryForMutation(UUID householdId, UUID categoryId, UUID retainedId) {
        CategoryRow category = loadCategory(householdId, categoryId);
        if ("ACTIVE".equals(category.status()) || categoryId.equals(retainedId)) {
            return category;
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "CATEGORY_NOT_ACTIVE");
    }

    private BeneficiaryRow requireBeneficiaryForMutation(
            UUID householdId, UUID beneficiaryId, UUID retainedId) {
        BeneficiaryRow beneficiary = loadBeneficiary(householdId, beneficiaryId);
        if ("ACTIVE".equals(beneficiary.status()) || beneficiaryId.equals(retainedId)) {
            return beneficiary;
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "BENEFICIARY_NOT_ACTIVE");
    }

    private CategoryRow loadCategory(UUID householdId, UUID categoryId) {
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
        return category;
    }

    private BeneficiaryRow loadBeneficiary(UUID householdId, UUID beneficiaryId) {
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
        return beneficiary;
    }

    private static void requireOwnerOrAdmin(LockedHouseholdActor locked, UUID createdByUserId) {
        if (!locked.admin() && !locked.userId().equals(createdByUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
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

    private static String normalizeReason(String reason) {
        String trimmed = reason.trim();
        if (trimmed.isEmpty() || trimmed.length() > 500 || containsLineBreak(trimmed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return trimmed;
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

    private record HeadRow(
            UUID id,
            UUID createdByUserId,
            long version,
            int revisionNo,
            String state,
            LocalDate effectiveDate,
            BigDecimal amount,
            String note,
            UUID accountId,
            UUID categoryId,
            UUID beneficiaryId) {}

    private record PostingRow(
            UUID id,
            UUID accountId,
            LocalDate effectiveDate,
            String currencyCode,
            BigDecimal signedAmount,
            int legNo) {}
}
