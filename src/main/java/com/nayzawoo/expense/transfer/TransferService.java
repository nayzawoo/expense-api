package com.nayzawoo.expense.transfer;

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
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
class TransferService {

    static final String CREATE_OPERATION = "POST /api/v1/households/{householdId}/transfers";
    static final String VOID_OPERATION = "POST /api/v1/households/{householdId}/transfers/{transferId}/void";

    private final FinancialAccess financialAccess;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    TransferService(
            FinancialAccess financialAccess, IdempotencyService idempotency, JdbcTemplate jdbc, Clock clock) {
        this.financialAccess = financialAccess;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    List<TransferView> list(ExpenseUser actor, UUID householdId) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        return jdbc.query(
                """
                SELECT t.id, t.kind, t.created_by_user_id, t.version, t.current_revision_no,
                       r.state, r.effective_date, r.amount, r.currency_code, r.note, r.recorded_at,
                       r.source_account_id, sa.name AS source_name,
                       r.destination_account_id, da.name AS destination_name,
                       u.display_name AS creator_display_name
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                JOIN account sa
                  ON sa.household_id = t.household_id
                 AND sa.id = r.source_account_id
                JOIN account da
                  ON da.household_id = t.household_id
                 AND da.id = r.destination_account_id
                JOIN app_user u
                  ON u.household_id = t.household_id
                 AND u.id = t.created_by_user_id
                WHERE t.household_id = ?
                  AND t.kind = 'TRANSFER'
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
                        rs.getObject("source_account_id", UUID.class),
                        rs.getString("source_name"),
                        rs.getObject("destination_account_id", UUID.class),
                        rs.getString("destination_name"),
                        rs.getObject("created_by_user_id", UUID.class),
                        rs.getString("creator_display_name"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getLong("version"),
                        rs.getInt("current_revision_no"),
                        locked.userId(),
                        locked.admin()),
                locked.householdId());
    }

    TransferView get(ExpenseUser actor, UUID householdId, UUID transferId) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        TransferView view = loadView(locked, transferId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return view;
    }

    @Transactional
    MutationResult create(
            ExpenseUser actor,
            UUID householdId,
            String idempotencyKey,
            LocalDate effectiveDate,
            String amountRaw,
            UUID sourceAccountId,
            UUID destinationAccountId,
            String note) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        if (effectiveDate == null
                || amountRaw == null
                || sourceAccountId == null
                || destinationAccountId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "SELF_TRANSFER");
        }
        BigDecimal amount = Money.parsePositivePrincipal(amountRaw);
        String normalizedNote = normalizeNote(note);
        LocalDate today = householdToday(locked.timezone());
        if (effectiveDate.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "FUTURE_DATE");
        }
        lockAccountsSorted(locked.householdId(), sourceAccountId, destinationAccountId);
        AccountRow source = requireActiveAccount(locked.householdId(), sourceAccountId);
        AccountRow destination = requireActiveAccount(locked.householdId(), destinationAccountId);
        if (effectiveDate.isBefore(source.openedOn()) || effectiveDate.isBefore(destination.openedOn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "DATE_BEFORE_OPENING");
        }
        String requestHash = IdempotencyService.hashRequest(IdempotencyService.canonical(
                householdId.toString(),
                effectiveDate.toString(),
                Money.format(amount),
                sourceAccountId.toString(),
                destinationAccountId.toString(),
                normalizedNote == null ? "" : normalizedNote));
        var existing = idempotency.begin(
                locked.householdId(), locked.userId(), CREATE_OPERATION, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            IdempotencyService.Replay replay = existing.get();
            return new MutationResult(true, replay.httpStatus(), replay.resourceId(), replay.responseBody());
        }
        Instant now = Instant.now(clock);
        UUID transactionId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO financial_transaction (
                    id, household_id, kind, created_by_user_id, current_revision_no, version,
                    opening_account_id, created_at)
                VALUES (?, ?, 'TRANSFER', ?, 1, 0, NULL, ?)
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
                VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, ?, ?, ?, NULL, ?, ?, NULL, NULL, ?, NULL,
                        ?, ?, NULL, ?, NULL, NULL)
                """,
                locked.householdId(),
                transactionId,
                Date.valueOf(effectiveDate),
                amount,
                locked.currency(),
                source.id(),
                destination.id(),
                normalizedNote,
                locked.userId(),
                Timestamp.from(now),
                actor.getDisplayName());
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
                source.id(),
                Date.valueOf(effectiveDate),
                locked.currency(),
                amount.negate(),
                Timestamp.from(now));
        jdbc.update(
                """
                INSERT INTO account_posting (
                    id, household_id, transaction_id, revision_no, account_id, effective_date,
                    currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, 2, 'APPLY', NULL, ?)
                """,
                UUID.randomUUID(),
                locked.householdId(),
                transactionId,
                destination.id(),
                Date.valueOf(effectiveDate),
                locked.currency(),
                amount,
                Timestamp.from(now));
        bumpBalanceVersions(locked.householdId(), now, source.id(), destination.id());
        TransferView view = new TransferView(
                transactionId,
                "TRANSFER",
                "POSTED",
                effectiveDate,
                Money.format(amount),
                locked.currency(),
                normalizedNote,
                new RefSummary(source.id(), source.name()),
                new RefSummary(destination.id(), destination.name()),
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
    MutationResult voidTransfer(
            ExpenseUser actor,
            UUID householdId,
            UUID transferId,
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
                transferId.toString(),
                Long.toString(expectedVersion),
                normalizedReason));
        var existing = idempotency.begin(
                locked.householdId(), locked.userId(), VOID_OPERATION, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            IdempotencyService.Replay replay = existing.get();
            return new MutationResult(true, replay.httpStatus(), replay.resourceId(), replay.responseBody());
        }
        HeadRow head = lockHead(locked.householdId(), transferId);
        requireOwnerOrAdmin(locked, head.createdByUserId());
        if (!"POSTED".equals(head.state())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        if (head.version() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH");
        }
        lockAccountsSorted(locked.householdId(), head.sourceAccountId(), head.destinationAccountId());
        AccountRow source =
                requireAccountForMutation(locked.householdId(), head.sourceAccountId(), head.sourceAccountId());
        AccountRow destination = requireAccountForMutation(
                locked.householdId(), head.destinationAccountId(), head.destinationAccountId());
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
                VALUES (?, ?, ?, 'VOID', 'VOID', ?, ?, ?, ?, NULL, ?, ?, NULL, NULL, ?, ?,
                        ?, ?, NULL, ?, NULL, NULL)
                """,
                locked.householdId(),
                transferId,
                newRevision,
                head.revisionNo(),
                Date.valueOf(head.effectiveDate()),
                head.amount(),
                locked.currency(),
                source.id(),
                destination.id(),
                head.note(),
                normalizedReason,
                locked.userId(),
                Timestamp.from(now),
                actor.getDisplayName());
        reverseApplyLegs(locked.householdId(), transferId, head.revisionNo(), newRevision, now);
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
                transferId,
                head.version());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "VERSION_MISMATCH");
        }
        bumpBalanceVersions(locked.householdId(), now, source.id(), destination.id());
        TransferView view = loadView(locked, transferId);
        String body = writeJson(view);
        idempotency.complete(
                locked.householdId(),
                locked.userId(),
                VOID_OPERATION,
                idempotencyKey,
                200,
                transferId,
                view.version(),
                body);
        return new MutationResult(false, 200, transferId, body);
    }

    private TransferView loadView(LockedHouseholdActor locked, UUID transferId) {
        List<TransferView> rows = jdbc.query(
                """
                SELECT t.id, t.kind, t.created_by_user_id, t.version, t.current_revision_no,
                       r.state, r.effective_date, r.amount, r.currency_code, r.note, r.recorded_at,
                       r.source_account_id, sa.name AS source_name,
                       r.destination_account_id, da.name AS destination_name,
                       u.display_name AS creator_display_name
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                JOIN account sa
                  ON sa.household_id = t.household_id
                 AND sa.id = r.source_account_id
                JOIN account da
                  ON da.household_id = t.household_id
                 AND da.id = r.destination_account_id
                JOIN app_user u
                  ON u.household_id = t.household_id
                 AND u.id = t.created_by_user_id
                WHERE t.household_id = ?
                  AND t.id = ?
                  AND t.kind = 'TRANSFER'
                """,
                (rs, rowNum) -> toView(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("state"),
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency_code"),
                        rs.getString("note"),
                        rs.getObject("source_account_id", UUID.class),
                        rs.getString("source_name"),
                        rs.getObject("destination_account_id", UUID.class),
                        rs.getString("destination_name"),
                        rs.getObject("created_by_user_id", UUID.class),
                        rs.getString("creator_display_name"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getLong("version"),
                        rs.getInt("current_revision_no"),
                        locked.userId(),
                        locked.admin()),
                locked.householdId(),
                transferId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private TransferView toView(
            UUID id,
            String kind,
            String state,
            LocalDate effectiveDate,
            BigDecimal amount,
            String currency,
            String note,
            UUID sourceAccountId,
            String sourceName,
            UUID destinationAccountId,
            String destinationName,
            UUID createdByUserId,
            String creatorDisplayName,
            Instant recordedAt,
            long version,
            int revisionNo,
            UUID actorUserId,
            boolean admin) {
        boolean canVoid = (admin || actorUserId.equals(createdByUserId)) && "POSTED".equals(state);
        return new TransferView(
                id,
                kind,
                state,
                effectiveDate,
                Money.format(amount),
                currency,
                note,
                new RefSummary(sourceAccountId, sourceName),
                new RefSummary(destinationAccountId, destinationName),
                new ActorSummary(createdByUserId, creatorDisplayName),
                recordedAt,
                version,
                revisionNo,
                canVoid);
    }

    private HeadRow lockHead(UUID householdId, UUID transferId) {
        HeadRow head = jdbc.query(
                """
                SELECT t.id, t.created_by_user_id, t.version, t.current_revision_no,
                       r.state, r.effective_date, r.amount, r.note,
                       r.source_account_id, r.destination_account_id
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                WHERE t.household_id = ?
                  AND t.id = ?
                  AND t.kind = 'TRANSFER'
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
                            rs.getObject("source_account_id", UUID.class),
                            rs.getObject("destination_account_id", UUID.class));
                },
                householdId,
                transferId);
        if (head == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return head;
    }

    private void lockAccountsSorted(UUID householdId, UUID... accountIds) {
        TreeSet<UUID> ordered = new TreeSet<>();
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
        if (applyLegs.size() != 2) {
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
        TreeSet<UUID> ordered = new TreeSet<>();
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

    private AccountRow requireActiveAccount(UUID householdId, UUID accountId) {
        AccountRow account = lockAccount(householdId, accountId);
        if (!"ACTIVE".equals(account.status())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "ACCOUNT_NOT_ACTIVE");
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

    private String writeJson(TransferView view) {
        try {
            return json.writeValueAsString(toResponse(view));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    static TransferResponse toResponse(TransferView view) {
        return new TransferResponse(
                view.id().toString(),
                view.kind(),
                view.state(),
                view.effectiveDate().toString(),
                view.amount(),
                view.currency(),
                view.note(),
                new RefResponse(view.sourceAccount().id().toString(), view.sourceAccount().name()),
                new RefResponse(
                        view.destinationAccount().id().toString(), view.destinationAccount().name()),
                new ActorResponse(view.createdBy().userId().toString(), view.createdBy().displayName()),
                view.recordedAt().toString(),
                view.version(),
                view.revisionNo(),
                new CapabilitiesResponse(view.canVoid()));
    }

    record TransferView(
            UUID id,
            String kind,
            String state,
            LocalDate effectiveDate,
            String amount,
            String currency,
            String note,
            RefSummary sourceAccount,
            RefSummary destinationAccount,
            ActorSummary createdBy,
            Instant recordedAt,
            long version,
            int revisionNo,
            boolean canVoid) {}

    record RefSummary(UUID id, String name) {}

    record ActorSummary(UUID userId, String displayName) {}

    record MutationResult(boolean replayed, int httpStatus, UUID resourceId, String responseBody) {}

    record TransferResponse(
            String id,
            String kind,
            String state,
            String effectiveDate,
            String amount,
            String currency,
            String note,
            RefResponse sourceAccount,
            RefResponse destinationAccount,
            ActorResponse createdBy,
            String recordedAt,
            long version,
            int revisionNo,
            CapabilitiesResponse capabilities) {}

    record RefResponse(String id, String name) {}

    record ActorResponse(String userId, String displayName) {}

    record CapabilitiesResponse(boolean canVoid) {}

    private record AccountRow(UUID id, String name, String status, LocalDate openedOn) {}

    private record HeadRow(
            UUID id,
            UUID createdByUserId,
            long version,
            int revisionNo,
            String state,
            LocalDate effectiveDate,
            BigDecimal amount,
            String note,
            UUID sourceAccountId,
            UUID destinationAccountId) {}

    private record PostingRow(
            UUID id,
            UUID accountId,
            LocalDate effectiveDate,
            String currencyCode,
            BigDecimal signedAmount,
            int legNo) {}
}
