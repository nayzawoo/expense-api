package com.nayzawoo.expense.report;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.identity.FinancialAccess;
import com.nayzawoo.expense.identity.FinancialAccess.LockedHouseholdActor;
import com.nayzawoo.expense.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
        BigDecimal expenseDifferencePercent = percentOrNull(expenseDifference, previousExpense);
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
        List<PacePointResponse> pace = spendingPace(locked.householdId(), asOf, period, comparison);
        Map<UUID, NamedAmount> currentByCategory =
                expenseByCategory(locked.householdId(), period.from(), period.to());
        Map<UUID, NamedAmount> previousByCategory =
                expenseByCategory(locked.householdId(), comparison.from(), comparison.to());
        List<CategoryBreakdownResponse> categoryBreakdown = categoryBreakdown(currentByCategory, expense);
        CategoryChangesResponse categoryChanges = categoryChanges(currentByCategory, previousByCategory);
        List<BeneficiarySpendingResponse> beneficiarySpending =
                beneficiarySpending(locked.householdId(), period.from(), period.to());
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
                        expenseDifferencePercent == null ? null : expenseDifferencePercent.toPlainString(),
                        direction,
                        null,
                        null),
                pace,
                categoryBreakdown,
                categoryChanges,
                beneficiarySpending,
                recent.stream().map(ActivityView::toResponse).toList());
    }

    private List<PacePointResponse> spendingPace(
            UUID householdId, LocalDate asOf, PeriodView period, PeriodView comparison) {
        Map<Integer, BigDecimal> currentDays = expenseByDayOfMonth(householdId, period.from(), period.to());
        Map<Integer, BigDecimal> previousDays =
                expenseByDayOfMonth(householdId, comparison.from(), comparison.to());
        int elapsedDays = asOf.getDayOfMonth();
        int previousLength = YearMonth.from(comparison.from()).lengthOfMonth();
        List<PacePointResponse> points = new ArrayList<>(elapsedDays);
        BigDecimal currentCumulative = BigDecimal.ZERO;
        BigDecimal previousCumulative = BigDecimal.ZERO;
        BigDecimal previousFinal = BigDecimal.ZERO;
        for (int day = 1; day <= previousLength; day++) {
            previousFinal = previousFinal.add(previousDays.getOrDefault(day, BigDecimal.ZERO));
        }
        for (int day = 1; day <= elapsedDays; day++) {
            BigDecimal dayExpense = currentDays.getOrDefault(day, BigDecimal.ZERO);
            currentCumulative = currentCumulative.add(dayExpense);
            if (day <= previousLength) {
                previousCumulative = previousCumulative.add(previousDays.getOrDefault(day, BigDecimal.ZERO));
            } else {
                previousCumulative = previousFinal;
            }
            points.add(new PacePointResponse(
                    day,
                    Money.format(currentCumulative),
                    Money.format(previousCumulative),
                    Money.format(dayExpense)));
        }
        return points;
    }

    private List<CategoryBreakdownResponse> categoryBreakdown(
            Map<UUID, NamedAmount> currentByCategory, BigDecimal totalExpense) {
        return currentByCategory.values().stream()
                .sorted(Comparator.comparing(NamedAmount::amount)
                        .reversed()
                        .thenComparing(NamedAmount::id))
                .map(row -> new CategoryBreakdownResponse(
                        new RefResponse(row.id().toString(), row.name()),
                        Money.format(row.amount()),
                        sharePercent(row.amount(), totalExpense).toPlainString()))
                .toList();
    }

    private CategoryChangesResponse categoryChanges(
            Map<UUID, NamedAmount> current, Map<UUID, NamedAmount> previous) {
        Map<UUID, String> names = new HashMap<>();
        for (NamedAmount row : current.values()) {
            names.put(row.id(), row.name());
        }
        for (NamedAmount row : previous.values()) {
            names.putIfAbsent(row.id(), row.name());
        }
        List<CategoryChangeResponse> all = new ArrayList<>();
        for (UUID id : names.keySet()) {
            BigDecimal currentAmount = current.containsKey(id) ? current.get(id).amount() : BigDecimal.ZERO;
            BigDecimal previousAmount = previous.containsKey(id) ? previous.get(id).amount() : BigDecimal.ZERO;
            BigDecimal difference = currentAmount.subtract(previousAmount);
            boolean isNew = previousAmount.compareTo(BigDecimal.ZERO) == 0
                    && currentAmount.compareTo(BigDecimal.ZERO) > 0;
            all.add(new CategoryChangeResponse(
                    new RefResponse(id.toString(), names.get(id)),
                    Money.format(currentAmount),
                    Money.format(previousAmount),
                    Money.format(difference),
                    percentOrNull(difference, previousAmount) == null
                            ? null
                            : percentOrNull(difference, previousAmount).toPlainString(),
                    isNew));
        }
        List<CategoryChangeResponse> increased = all.stream()
                .filter(change -> new BigDecimal(change.difference()).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator.comparing((CategoryChangeResponse change) -> new BigDecimal(change.difference()))
                        .reversed()
                        .thenComparing(change -> change.category().id()))
                .limit(3)
                .toList();
        List<CategoryChangeResponse> decreased = all.stream()
                .filter(change -> new BigDecimal(change.difference()).compareTo(BigDecimal.ZERO) < 0)
                .sorted(Comparator.comparing((CategoryChangeResponse change) -> new BigDecimal(change.difference()))
                        .thenComparing(change -> change.category().id()))
                .limit(3)
                .toList();
        return new CategoryChangesResponse(increased, decreased);
    }

    private List<BeneficiarySpendingResponse> beneficiarySpending(UUID householdId, LocalDate from, LocalDate to) {
        return expenseByBeneficiary(householdId, from, to).values().stream()
                .sorted(Comparator.comparing(NamedAmount::amount)
                        .reversed()
                        .thenComparing(NamedAmount::id))
                .map(row -> new BeneficiarySpendingResponse(
                        new RefResponse(row.id().toString(), row.name()), Money.format(row.amount())))
                .toList();
    }

    private Map<Integer, BigDecimal> expenseByDayOfMonth(UUID householdId, LocalDate from, LocalDate to) {
        Map<Integer, BigDecimal> days = new HashMap<>();
        jdbc.query(
                """
                SELECT EXTRACT(DAY FROM r.effective_date)::int AS day_no, COALESCE(SUM(r.amount), 0)
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                WHERE t.household_id = ?
                  AND t.kind = 'EXPENSE'
                  AND r.state = 'POSTED'
                  AND r.effective_date >= ?
                  AND r.effective_date <= ?
                GROUP BY EXTRACT(DAY FROM r.effective_date)
                """,
                rs -> {
                    days.put(rs.getInt(1), rs.getBigDecimal(2));
                },
                householdId,
                Date.valueOf(from),
                Date.valueOf(to));
        return days;
    }

    private Map<UUID, NamedAmount> expenseByCategory(UUID householdId, LocalDate from, LocalDate to) {
        Map<UUID, NamedAmount> rows = new HashMap<>();
        jdbc.query(
                """
                SELECT c.id, c.name, COALESCE(SUM(r.amount), 0)
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                JOIN category c
                  ON c.household_id = t.household_id
                 AND c.id = r.category_id
                WHERE t.household_id = ?
                  AND t.kind = 'EXPENSE'
                  AND r.state = 'POSTED'
                  AND r.effective_date >= ?
                  AND r.effective_date <= ?
                GROUP BY c.id, c.name
                """,
                rs -> {
                    UUID id = rs.getObject(1, UUID.class);
                    rows.put(id, new NamedAmount(id, rs.getString(2), rs.getBigDecimal(3)));
                },
                householdId,
                Date.valueOf(from),
                Date.valueOf(to));
        return rows;
    }

    private Map<UUID, NamedAmount> expenseByBeneficiary(UUID householdId, LocalDate from, LocalDate to) {
        Map<UUID, NamedAmount> rows = new HashMap<>();
        jdbc.query(
                """
                SELECT b.id, b.name, COALESCE(SUM(r.amount), 0)
                FROM financial_transaction t
                JOIN transaction_revision r
                  ON r.household_id = t.household_id
                 AND r.transaction_id = t.id
                 AND r.revision_no = t.current_revision_no
                JOIN beneficiary b
                  ON b.household_id = t.household_id
                 AND b.id = r.beneficiary_id
                WHERE t.household_id = ?
                  AND t.kind = 'EXPENSE'
                  AND r.state = 'POSTED'
                  AND r.effective_date >= ?
                  AND r.effective_date <= ?
                GROUP BY b.id, b.name
                """,
                rs -> {
                    UUID id = rs.getObject(1, UUID.class);
                    rows.put(id, new NamedAmount(id, rs.getString(2), rs.getBigDecimal(3)));
                },
                householdId,
                Date.valueOf(from),
                Date.valueOf(to));
        return rows;
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
        return new PeriodView(asOf.withDayOfMonth(1), asOf);
    }

    private static PeriodView comparisonPeriod(LocalDate asOf) {
        YearMonth previous = YearMonth.from(asOf).minusMonths(1);
        int day = Math.min(asOf.getDayOfMonth(), previous.lengthOfMonth());
        return new PeriodView(previous.atDay(1), previous.atDay(day));
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

    private static BigDecimal percentOrNull(BigDecimal numerator, BigDecimal denominator) {
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return numerator.multiply(new BigDecimal("100")).divide(denominator, 1, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal sharePercent(BigDecimal amount, BigDecimal totalExpense) {
        if (totalExpense.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return amount.multiply(new BigDecimal("100")).divide(totalExpense, 1, RoundingMode.HALF_EVEN);
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
            List<PacePointResponse> spendingPace,
            List<CategoryBreakdownResponse> categoryBreakdown,
            CategoryChangesResponse categoryChanges,
            List<BeneficiarySpendingResponse> beneficiarySpending,
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

    record AccountBalanceResponse(String id, String name, String balance, boolean negativeBalanceWarning) {}

    record RefResponse(String id, String name) {}

    record PacePointResponse(int day, String currentCumulative, String previousCumulative, String expense) {}

    record CategoryBreakdownResponse(RefResponse category, String amount, String sharePercent) {}

    record CategoryChangeResponse(
            RefResponse category,
            String currentAmount,
            String previousAmount,
            String difference,
            String differencePercent,
            boolean isNew) {}

    record CategoryChangesResponse(List<CategoryChangeResponse> increased, List<CategoryChangeResponse> decreased) {}

    record BeneficiarySpendingResponse(RefResponse beneficiary, String amount) {}

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

    private record NamedAmount(UUID id, String name, BigDecimal amount) {}
}
