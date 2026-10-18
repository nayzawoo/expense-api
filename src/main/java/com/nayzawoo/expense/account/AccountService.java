package com.nayzawoo.expense.account;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.identity.FinancialAccess;
import com.nayzawoo.expense.identity.FinancialAccess.LockedHouseholdActor;
import com.nayzawoo.expense.shared.Money;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
class AccountService {

    static final String CREATE_OPERATION = "POST /api/v1/households/{householdId}/accounts";

    private final FinancialAccess financialAccess;
    private final AccountRepository accounts;
    private final OpeningWriteService openings;
    private final IdempotencyService idempotency;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    AccountService(
            FinancialAccess financialAccess,
            AccountRepository accounts,
            OpeningWriteService openings,
            IdempotencyService idempotency,
            JdbcTemplate jdbc,
            Clock clock) {
        this.financialAccess = financialAccess;
        this.accounts = accounts;
        this.openings = openings;
        this.idempotency = idempotency;
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
    }

    List<AccountView> list(ExpenseUser actor, UUID householdId) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        List<Account> rows = accounts.findByHouseholdIdOrderBySortOrderAscNameAscIdAsc(locked.householdId());
        if (rows.isEmpty()) {
            return List.of();
        }
        LocalDate asOf = householdToday(locked.timezone());
        Map<UUID, BigDecimal> balances = balancesAsOf(locked.householdId(), rows, asOf);
        Map<UUID, BigDecimal> openings = openingAmounts(locked.householdId(), rows);
        List<AccountView> views = new ArrayList<>(rows.size());
        for (Account account : rows) {
            views.add(toView(account, openings.get(account.getId()), balances.get(account.getId()), asOf));
        }
        return views;
    }

    AccountView get(ExpenseUser actor, UUID householdId, UUID accountId) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        Account account = accounts
                .findByIdAndHouseholdId(accountId, locked.householdId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        LocalDate asOf = householdToday(locked.timezone());
        Map<UUID, BigDecimal> balances = balancesAsOf(locked.householdId(), List.of(account), asOf);
        Map<UUID, BigDecimal> openings = openingAmounts(locked.householdId(), List.of(account));
        return toView(account, openings.get(account.getId()), balances.get(account.getId()), asOf);
    }

    LedgerView ledger(
            ExpenseUser actor,
            UUID householdId,
            UUID accountId,
            LocalDate from,
            LocalDate to,
            Integer page,
            Integer size) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        Account account = accounts
                .findByIdAndHouseholdId(accountId, locked.householdId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        LocalDate today = householdToday(locked.timezone());
        LocalDate resolvedFrom = from == null ? account.getOpenedOn() : from;
        LocalDate resolvedTo = to == null ? today : to;
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? 30 : size;
        if (resolvedPage < 0 || resolvedSize < 1 || resolvedSize > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (resolvedFrom.isBefore(account.getOpenedOn())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "DATE_BEFORE_OPENING");
        }
        if (resolvedTo.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "FUTURE_DATE");
        }
        MapSqlParameterSource rangeParams = new MapSqlParameterSource()
                .addValue("householdId", locked.householdId())
                .addValue("accountId", account.getId())
                .addValue("from", Date.valueOf(resolvedFrom))
                .addValue("to", Date.valueOf(resolvedTo));
        BigDecimal openingBalance = jdbc.query(
                """
                SELECT COALESCE(SUM(signed_amount), 0)
                FROM account_posting
                WHERE household_id = :householdId
                  AND account_id = :accountId
                  AND effective_date < :from
                """,
                rangeParams,
                rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO);
        BigDecimal closingBalance = jdbc.query(
                """
                SELECT COALESCE(SUM(signed_amount), 0)
                FROM account_posting
                WHERE household_id = :householdId
                  AND account_id = :accountId
                  AND effective_date <= :to
                """,
                rangeParams,
                rs -> rs.next() ? rs.getBigDecimal(1) : BigDecimal.ZERO);
        Long totalElements = jdbc.query(
                """
                SELECT count(*)
                FROM account_posting
                WHERE household_id = :householdId
                  AND account_id = :accountId
                  AND effective_date >= :from
                  AND effective_date <= :to
                """,
                rangeParams,
                rs -> rs.next() ? rs.getLong(1) : 0L);
        long total = totalElements == null ? 0L : totalElements;
        int totalPages = resolvedSize == 0 ? 0 : (int) ((total + resolvedSize - 1) / resolvedSize);
        MapSqlParameterSource pageParams = new MapSqlParameterSource()
                .addValue("householdId", locked.householdId())
                .addValue("accountId", account.getId())
                .addValue("from", Date.valueOf(resolvedFrom))
                .addValue("to", Date.valueOf(resolvedTo))
                .addValue("limit", resolvedSize)
                .addValue("offset", resolvedPage * resolvedSize);
        List<LedgerEntryView> items = jdbc.query(
                """
                WITH ranked AS (
                    SELECT p.id AS posting_id,
                           p.transaction_id,
                           t.kind,
                           p.role,
                           p.signed_amount,
                           p.effective_date,
                           p.recorded_at,
                           p.revision_no,
                           r.recorded_by_user_id,
                           r.actor_display_name_snapshot,
                           SUM(p.signed_amount) OVER (
                               ORDER BY p.effective_date ASC, p.recorded_at ASC, p.id ASC
                               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                           ) AS running_balance
                    FROM account_posting p
                    JOIN financial_transaction t
                      ON t.household_id = p.household_id
                     AND t.id = p.transaction_id
                    JOIN transaction_revision r
                      ON r.household_id = p.household_id
                     AND r.transaction_id = p.transaction_id
                     AND r.revision_no = p.revision_no
                    WHERE p.household_id = :householdId
                      AND p.account_id = :accountId
                      AND p.effective_date <= :to
                )
                SELECT posting_id, transaction_id, kind, role, signed_amount, effective_date, recorded_at,
                       revision_no, recorded_by_user_id, actor_display_name_snapshot, running_balance
                FROM ranked
                WHERE effective_date >= :from
                ORDER BY effective_date ASC, recorded_at ASC, posting_id ASC
                LIMIT :limit OFFSET :offset
                """,
                pageParams,
                (rs, rowNum) -> new LedgerEntryView(
                        rs.getObject("posting_id", UUID.class),
                        rs.getObject("transaction_id", UUID.class),
                        rs.getString("kind"),
                        rs.getString("role"),
                        Money.format(rs.getBigDecimal("signed_amount")),
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getInt("revision_no"),
                        new ActorSummary(
                                rs.getObject("recorded_by_user_id", UUID.class),
                                rs.getString("actor_display_name_snapshot")),
                        Money.format(rs.getBigDecimal("running_balance"))));
        return new LedgerView(
                account.getId(),
                account.getCurrencyCode(),
                resolvedFrom,
                resolvedTo,
                Money.format(openingBalance == null ? BigDecimal.ZERO : openingBalance),
                Money.format(closingBalance == null ? BigDecimal.ZERO : closingBalance),
                resolvedPage,
                resolvedSize,
                total,
                totalPages,
                items);
    }

    @Transactional
    CreateResult create(
            ExpenseUser actor,
            UUID householdId,
            String idempotencyKey,
            String name,
            String type,
            String ownerLabel,
            LocalDate openedOn,
            String openingAmountRaw,
            Integer sortOrder) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        if (!locked.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (name == null
                || name.isBlank()
                || ownerLabel == null
                || ownerLabel.isBlank()
                || type == null
                || !isType(type)
                || openedOn == null
                || sortOrder == null
                || openingAmountRaw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        BigDecimal openingAmount = Money.parseSignedOpening(openingAmountRaw);
        LocalDate today = householdToday(locked.timezone());
        if (openedOn.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "FUTURE_DATE");
        }
        String trimmedName = name.trim();
        String trimmedOwner = ownerLabel.trim();
        if (trimmedName.length() > 120
                || trimmedOwner.length() > 120
                || containsLineBreak(trimmedName)
                || containsLineBreak(trimmedOwner)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String requestHash = IdempotencyService.hashRequest(IdempotencyService.canonical(
                householdId.toString(),
                trimmedName,
                type,
                trimmedOwner,
                openedOn.toString(),
                Money.format(openingAmount),
                Integer.toString(sortOrder)));
        var existing = idempotency.begin(
                locked.householdId(), locked.userId(), CREATE_OPERATION, idempotencyKey, requestHash);
        if (existing.isPresent()) {
            IdempotencyService.Replay replay = existing.get();
            return new CreateResult(true, replay.httpStatus(), replay.resourceId(), replay.responseBody());
        }
        Instant now = Instant.now(clock);
        UUID accountId = UUID.randomUUID();
        Account account = Account.create(
                accountId,
                locked.householdId(),
                trimmedName,
                trimmedOwner,
                type,
                locked.currency(),
                openedOn,
                sortOrder,
                now);
        accounts.saveAndFlush(account);
        openings.createOpening(
                locked.householdId(),
                accountId,
                locked.userId(),
                actor.getDisplayName(),
                trimmedName,
                openedOn,
                openingAmount,
                locked.currency(),
                now);
        AccountView view = toView(account, openingAmount, balancesAsOf(locked.householdId(), List.of(account), today)
                .get(accountId), today);
        String body = writeJson(view);
        idempotency.complete(
                locked.householdId(),
                locked.userId(),
                CREATE_OPERATION,
                idempotencyKey,
                201,
                accountId,
                view.version(),
                body);
        return new CreateResult(false, 201, accountId, body);
    }

    private LocalDate householdToday(String timezone) {
        return LocalDate.now(clock.withZone(ZoneId.of(timezone)));
    }

    private Map<UUID, BigDecimal> balancesAsOf(UUID householdId, List<Account> accounts, LocalDate asOf) {
        Map<UUID, BigDecimal> balances = new HashMap<>();
        for (Account account : accounts) {
            balances.put(account.getId(), BigDecimal.ZERO);
        }
        List<UUID> ids = accounts.stream().map(Account::getId).toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("householdId", householdId)
                .addValue("ids", ids)
                .addValue("asOf", Date.valueOf(asOf));
        jdbc.query(
                """
                SELECT account_id, COALESCE(SUM(signed_amount), 0)
                FROM account_posting
                WHERE household_id = :householdId
                  AND account_id IN (:ids)
                  AND effective_date <= :asOf
                GROUP BY account_id
                """,
                params,
                rs -> {
                    balances.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                });
        return balances;
    }

    private Map<UUID, BigDecimal> openingAmounts(UUID householdId, List<Account> accounts) {
        Map<UUID, BigDecimal> openings = new HashMap<>();
        List<UUID> ids = accounts.stream().map(Account::getId).toList();
        MapSqlParameterSource params =
                new MapSqlParameterSource().addValue("householdId", householdId).addValue("ids", ids);
        jdbc.query(
                """
                SELECT t.opening_account_id, r.amount
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                WHERE t.household_id = :householdId
                  AND t.kind = 'OPENING'
                  AND t.opening_account_id IN (:ids)
                """,
                params,
                rs -> {
                    openings.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                });
        for (Account account : accounts) {
            if (!openings.containsKey(account.getId())) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }
        return openings;
    }

    private AccountView toView(Account account, BigDecimal openingAmount, BigDecimal balance, LocalDate asOf) {
        if (openingAmount == null || balance == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return new AccountView(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getOwnerLabel(),
                account.getOpenedOn(),
                Money.format(openingAmount),
                Money.format(balance),
                asOf,
                account.getBalanceVersion(),
                balance.compareTo(BigDecimal.ZERO) < 0,
                account.getStatus(),
                account.getSortOrder(),
                account.getVersion(),
                account.getCurrencyCode());
    }

    private String writeJson(AccountView view) {
        try {
            return json.writeValueAsString(view);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private static boolean isType(String type) {
        return "BANK".equals(type) || "WALLET".equals(type) || "CASH".equals(type);
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    record AccountView(
            UUID id,
            String name,
            String type,
            String ownerLabel,
            LocalDate openedOn,
            String openingAmount,
            String currentBalance,
            LocalDate balanceAsOf,
            long balanceVersion,
            boolean negativeBalanceWarning,
            String status,
            int sortOrder,
            long version,
            String currency) {}

    record CreateResult(boolean replayed, int httpStatus, UUID resourceId, String responseBody) {}

    record ActorSummary(UUID userId, String displayName) {}

    record LedgerEntryView(
            UUID postingId,
            UUID transactionId,
            String kind,
            String role,
            String signedAmount,
            LocalDate effectiveDate,
            Instant recordedAt,
            int revisionNo,
            ActorSummary actor,
            String runningBalance) {}

    record LedgerView(
            UUID accountId,
            String currency,
            LocalDate from,
            LocalDate to,
            String openingBalance,
            String closingBalance,
            int page,
            int size,
            long totalElements,
            int totalPages,
            List<LedgerEntryView> items) {}
}
