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
}
