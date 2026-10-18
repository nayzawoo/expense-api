package com.nayzawoo.expense.report;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.report.ReportService.AccountBalanceResponse;
import com.nayzawoo.expense.report.ReportService.ActivityResponse;
import com.nayzawoo.expense.report.ReportService.DashboardView;
import com.nayzawoo.expense.report.ReportService.OverviewView;
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

    record DashboardResponse(
            String currency,
            String timeZone,
            String asOf,
            PeriodResponse period,
            PeriodResponse comparisonPeriod,
            String totalBalance,
            List<AccountBalanceResponse> accounts,
            OverviewView overview,
            List<Object> spendingPace,
            List<Object> categoryBreakdown,
            List<Object> categoryChanges,
            List<Object> beneficiarySpending,
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
