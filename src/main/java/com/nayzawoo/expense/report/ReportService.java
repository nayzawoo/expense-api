package com.nayzawoo.expense.report;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.identity.FinancialAccess;
import com.nayzawoo.expense.identity.FinancialAccess.LockedHouseholdActor;
import com.nayzawoo.expense.shared.Money;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class ReportService {

    private final FinancialAccess financialAccess;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ReportService(FinancialAccess financialAccess, JdbcTemplate jdbc, Clock clock) {
        this.financialAccess = financialAccess;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    DashboardView dashboard(ExpenseUser actor, UUID householdId, LocalDate asOfRaw) {
        LockedHouseholdActor locked = financialAccess.requireHouseholdMember(actor, householdId);
        LocalDate today = householdToday(locked.timezone());
        LocalDate asOf = asOfRaw == null ? today : asOfRaw;
        if (asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "FUTURE_DATE");
        }
        PeriodView period = currentPeriod(asOf);
        PeriodView comparison = comparisonPeriod(asOf);
        BigDecimal expense = flowTotal(locked.householdId(), "EXPENSE", period.from(), period.to());
        BigDecimal income = flowTotal(locked.householdId(), "INCOME", period.from(), period.to());
        BigDecimal previousExpense =
                flowTotal(locked.householdId(), "EXPENSE", comparison.from(), comparison.to());
        BigDecimal net = income.subtract(expense);
        BigDecimal expenseDifference = expense.subtract(previousExpense);
        String direction = directionOf(expenseDifference);
        List<AccountBalanceView> allAccounts = accountBalances(locked.householdId(), asOf);
        BigDecimal totalBalance = allAccounts.stream()
                .map(AccountBalanceView::balanceValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<AccountBalanceView> preview = allAccounts.stream()
                .sorted(Comparator.comparing(AccountBalanceView::balanceValue)
                        .reversed()
                        .thenComparing(AccountBalanceView::id))
                .limit(6)
                .toList();
        List<ActivityView> recent = recentActivity(locked.householdId(), asOf);
        return new DashboardView(
                locked.currency(),
                locked.timezone(),
                asOf,
                period,
                comparison,
                Money.format(totalBalance),
                preview.stream().map(AccountBalanceView::toResponse).toList(),
                new OverviewView(
                        Money.format(expense),
                        Money.format(income),
                        Money.format(net),
                        Money.format(previousExpense),
                        Money.format(expenseDifference),
                        null,
                        direction,
                        null,
                        null),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                recent.stream().map(ActivityView::toResponse).toList());
    }

    private List<AccountBalanceView> accountBalances(UUID householdId, LocalDate asOf) {
        List<AccountRow> accounts = jdbc.query(
                """
                SELECT id, name, opened_on
                FROM account
                WHERE household_id = ?
                ORDER BY id ASC
                """,
                (rs, rowNum) -> new AccountRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getDate("opened_on").toLocalDate()),
                householdId);
        Map<UUID, BigDecimal> balances = new HashMap<>();
        for (AccountRow account : accounts) {
            if (!account.openedOn().isAfter(asOf)) {
                balances.put(account.id(), BigDecimal.ZERO);
            }
        }
        if (!balances.isEmpty()) {
            List<UUID> ids = new ArrayList<>(balances.keySet());
            String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
            List<Object> args = new ArrayList<>();
            args.add(householdId);
            args.addAll(ids);
            args.add(Date.valueOf(asOf));
            jdbc.query(
                    """
                    SELECT account_id, COALESCE(SUM(signed_amount), 0)
                    FROM account_posting
                    WHERE household_id = ?
                      AND account_id IN (%s)
                      AND effective_date <= ?
                    GROUP BY account_id
                    """
                            .formatted(placeholders),
                    rs -> {
                        balances.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2));
                    },
                    args.toArray());
        }
        List<AccountBalanceView> views = new ArrayList<>();
        for (AccountRow account : accounts) {
            if (account.openedOn().isAfter(asOf)) {
                continue;
            }
            BigDecimal balance = balances.getOrDefault(account.id(), BigDecimal.ZERO);
            views.add(new AccountBalanceView(
                    account.id(), account.name(), balance, balance.compareTo(BigDecimal.ZERO) < 0));
        }
        return views;
    }

    private BigDecimal flowTotal(UUID householdId, String kind, LocalDate from, LocalDate to) {
        BigDecimal total = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(r.amount), 0)
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                WHERE t.household_id = ?
                  AND t.kind = ?
                  AND r.state = 'POSTED'
                  AND r.effective_date >= ?
                  AND r.effective_date <= ?
                """,
                BigDecimal.class,
                householdId,
                kind,
                Date.valueOf(from),
                Date.valueOf(to));
        return total == null ? BigDecimal.ZERO : total;
    }

    private List<ActivityView> recentActivity(UUID householdId, LocalDate asOf) {
        return jdbc.query(
                """
                SELECT t.id, t.kind, r.effective_date, r.amount, r.currency_code, r.note, r.recorded_at,
                       r.account_id, a.name AS account_name,
                       r.source_account_id, sa.name AS source_name,
                       r.destination_account_id, da.name AS destination_name
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                LEFT JOIN account a
                  ON a.household_id = t.household_id
                 AND a.id = r.account_id
                LEFT JOIN account sa
                  ON sa.household_id = t.household_id
                 AND sa.id = r.source_account_id
                LEFT JOIN account da
                  ON da.household_id = t.household_id
                 AND da.id = r.destination_account_id
                WHERE t.household_id = ?
                  AND t.kind IN ('EXPENSE', 'INCOME', 'TRANSFER')
                  AND r.state = 'POSTED'
                  AND r.effective_date <= ?
                ORDER BY r.recorded_at DESC, t.id DESC
                LIMIT 8
                """,
                (rs, rowNum) -> new ActivityView(
                        rs.getObject("id", UUID.class),
                        rs.getString("kind"),
                        rs.getDate("effective_date").toLocalDate(),
                        Money.format(rs.getBigDecimal("amount")),
                        rs.getString("currency_code"),
                        rs.getString("note"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        refOrNull(rs.getObject("account_id", UUID.class), rs.getString("account_name")),
                        refOrNull(rs.getObject("source_account_id", UUID.class), rs.getString("source_name")),
                        refOrNull(
                                rs.getObject("destination_account_id", UUID.class),
                                rs.getString("destination_name"))),
                householdId,
                Date.valueOf(asOf));
    }

    private static RefView refOrNull(UUID id, String name) {
        if (id == null) {
            return null;
        }
        return new RefView(id, name);
    }

    private static PeriodView currentPeriod(LocalDate asOf) {
        LocalDate from = asOf.withDayOfMonth(1);
        return new PeriodView(from, asOf);
    }

    private static PeriodView comparisonPeriod(LocalDate asOf) {
        YearMonth previous = YearMonth.from(asOf).minusMonths(1);
        LocalDate from = previous.atDay(1);
        int day = Math.min(asOf.getDayOfMonth(), previous.lengthOfMonth());
        LocalDate to = previous.atDay(day);
        return new PeriodView(from, to);
    }

    private static String directionOf(BigDecimal difference) {
        int cmp = difference.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
            return "UP";
        }
        if (cmp < 0) {
            return "DOWN";
        }
        return "FLAT";
    }

    private LocalDate householdToday(String timezone) {
        return LocalDate.now(clock.withZone(ZoneId.of(timezone)));
    }

    record DashboardView(
            String currency,
            String timeZone,
            LocalDate asOf,
            PeriodView period,
            PeriodView comparisonPeriod,
            String totalBalance,
            List<AccountBalanceResponse> accounts,
            OverviewView overview,
            List<Object> spendingPace,
            List<Object> categoryBreakdown,
            List<Object> categoryChanges,
            List<Object> beneficiarySpending,
            List<ActivityResponse> recentActivity) {}

    record PeriodView(LocalDate from, LocalDate to) {
        PeriodResponse toResponse() {
            return new PeriodResponse(from.toString(), to.toString());
        }
    }

    record OverviewView(
            String expense,
            String income,
            String net,
            String previousSamePeriodExpense,
            String expenseDifference,
            String expenseDifferencePercent,
            String direction,
            String previousFiveMonthAverage,
            String elapsedDayAverage) {}

    record AccountBalanceView(UUID id, String name, BigDecimal balanceValue, boolean negativeBalanceWarning) {
        AccountBalanceResponse toResponse() {
            return new AccountBalanceResponse(
                    id.toString(), name, Money.format(balanceValue), negativeBalanceWarning);
        }
    }

    record ActivityView(
            UUID id,
            String kind,
            LocalDate effectiveDate,
            String amount,
            String currency,
            String note,
            Instant recordedAt,
            RefView account,
            RefView sourceAccount,
            RefView destinationAccount) {
        ActivityResponse toResponse() {
            return new ActivityResponse(
                    id.toString(),
                    kind,
                    effectiveDate.toString(),
                    amount,
                    currency,
                    note,
                    recordedAt.toString(),
                    account == null ? null : account.toResponse(),
                    sourceAccount == null ? null : sourceAccount.toResponse(),
                    destinationAccount == null ? null : destinationAccount.toResponse());
        }
    }

    record RefView(UUID id, String name) {
        RefResponse toResponse() {
            return new RefResponse(id.toString(), name);
        }
    }

    record PeriodResponse(String from, String to) {}

    record AccountBalanceResponse(
            String id, String name, String balance, boolean negativeBalanceWarning) {}

    record RefResponse(String id, String name) {}

    record ActivityResponse(
            String id,
            String kind,
            String effectiveDate,
            String amount,
            String currency,
            String note,
            String recordedAt,
            RefResponse account,
            RefResponse sourceAccount,
            RefResponse destinationAccount) {}

    private record AccountRow(UUID id, String name, LocalDate openedOn) {}
}
