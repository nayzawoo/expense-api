package com.nayzawoo.expense.account;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.identity.FinancialAccess;
import com.nayzawoo.expense.identity.FinancialAccess.LockedHouseholdActor;
import com.nayzawoo.expense.shared.Money;
import java.math.BigDecimal;
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
class AccountService {

    static final String CREATE_OPERATION = "POST /api/v1/households/{householdId}/accounts";

    private final FinancialAccess financialAccess;
    private final AccountRepository accounts;
    private final OpeningWriteService openings;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
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
        this.jdbc = jdbc;
        this.clock = clock;
    }

    List<AccountView> list(ExpenseUser actor, UUID householdId) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        return accounts.findByHouseholdIdOrderBySortOrderAscNameAscIdAsc(locked.householdId()).stream()
                .map(this::toView)
                .toList();
    }

    AccountView get(ExpenseUser actor, UUID householdId, UUID accountId) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        Account account = accounts
                .findByIdAndHouseholdId(accountId, locked.householdId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return toView(account);
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
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(locked.timezone())));
        if (openedOn.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "FUTURE_DATE");
        }
        String trimmedName = name.trim();
        String trimmedOwner = ownerLabel.trim();
        if (trimmedName.length() > 120 || trimmedOwner.length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String requestHash = IdempotencyService.hashRequest(String.join(
                "\n",
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
        AccountView view = toView(account);
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

    private AccountView toView(Account account) {
        BigDecimal balance = jdbc.query(
                """
                SELECT COALESCE(SUM(signed_amount), 0)
                FROM account_posting
                WHERE household_id = ? AND account_id = ?
                """,
                rs -> {
                    rs.next();
                    return rs.getBigDecimal(1);
                },
                account.getHouseholdId(),
                account.getId());
        BigDecimal openingAmount = jdbc.query(
                """
                SELECT r.amount
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                WHERE t.household_id = ?
                  AND t.opening_account_id = ?
                  AND t.kind = 'OPENING'
                """,
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                    return rs.getBigDecimal(1);
                },
                account.getHouseholdId(),
                account.getId());
        return new AccountView(
                account.getId(),
                account.getName(),
                account.getType(),
                account.getOwnerLabel(),
                account.getOpenedOn(),
                Money.format(openingAmount),
                Money.format(balance),
                account.getOpenedOn(),
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
