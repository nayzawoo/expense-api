package com.nayzawoo.expense.report;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.report.ReportService.AccountBalanceResponse;
import com.nayzawoo.expense.report.ReportService.ActivityResponse;
import com.nayzawoo.expense.report.ReportService.AnalyticsView;
import com.nayzawoo.expense.report.ReportService.BeneficiarySpendingResponse;
import com.nayzawoo.expense.report.ReportService.CategoryBreakdownResponse;
import com.nayzawoo.expense.report.ReportService.CategoryChangesResponse;
import com.nayzawoo.expense.report.ReportService.DashboardView;
import com.nayzawoo.expense.report.ReportService.MonthTrendResponse;
import com.nayzawoo.expense.report.ReportService.OverviewView;
import com.nayzawoo.expense.report.ReportService.PacePointResponse;
import com.nayzawoo.expense.report.ReportService.PeriodResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/{householdId}/reports")
class ReportController {

    private final ReportService reports;

    ReportController(ReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/dashboard")
    DashboardResponse dashboard(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestParam(required = false) LocalDate asOf) {
        return DashboardResponse.from(reports.dashboard(actor, householdId, asOf));
    }

    @GetMapping("/analytics")
    AnalyticsResponse analytics(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestParam(required = false) LocalDate asOf) {
        return AnalyticsResponse.from(reports.analytics(actor, householdId, asOf));
    }

    record AnalyticsResponse(
            String currency,
            String timeZone,
            String asOf,
            PeriodResponse period,
            PeriodResponse comparisonPeriod,
            OverviewView overview,
            List<MonthTrendResponse> monthlyTrend,
            List<CategoryBreakdownResponse> categoryBreakdown,
            CategoryChangesResponse categoryChanges,
            Object insight) {

        static AnalyticsResponse from(AnalyticsView view) {
            return new AnalyticsResponse(
                    view.currency(),
                    view.timeZone(),
                    view.asOf().toString(),
                    view.period().toResponse(),
                    view.comparisonPeriod().toResponse(),
                    view.overview(),
                    view.monthlyTrend(),
                    view.categoryBreakdown(),
                    view.categoryChanges(),
                    view.insight());
        }
    }

    record DashboardResponse(
            String currency,
            String timeZone,
            String asOf,
            PeriodResponse period,
            PeriodResponse comparisonPeriod,
            String totalBalance,
            List<AccountBalanceResponse> accounts,
            OverviewView overview,
            List<PacePointResponse> spendingPace,
            List<CategoryBreakdownResponse> categoryBreakdown,
            CategoryChangesResponse categoryChanges,
            List<BeneficiarySpendingResponse> beneficiarySpending,
            List<ActivityResponse> recentActivity) {

        static DashboardResponse from(DashboardView view) {
            return new DashboardResponse(
                    view.currency(),
                    view.timeZone(),
                    view.asOf().toString(),
                    view.period().toResponse(),
                    view.comparisonPeriod().toResponse(),
                    view.totalBalance(),
                    view.accounts(),
                    view.overview(),
                    view.spendingPace(),
                    view.categoryBreakdown(),
                    view.categoryChanges(),
                    view.beneficiarySpending(),
                    view.recentActivity());
        }
    }
}
