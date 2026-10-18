package com.nayzawoo.expense.income;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.income.IncomeService.IncomeResponse;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/{householdId}/incomes")
class IncomeController {

    private final IncomeService incomes;

    IncomeController(IncomeService incomes) {
        this.incomes = incomes;
    }

    @GetMapping
    List<IncomeResponse> list(@AuthenticationPrincipal ExpenseUser actor, @PathVariable UUID householdId) {
        return incomes.list(actor, householdId).stream().map(IncomeService::toResponse).toList();
    }

    @GetMapping("/{incomeId}")
    IncomeResponse detail(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID incomeId) {
        return IncomeService.toResponse(incomes.get(actor, householdId, incomeId));
    }

    @PostMapping
    ResponseEntity<String> create(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateIncomeRequest request) {
        IncomeService.MutationResult result = incomes.create(
                actor,
                householdId,
                idempotencyKey,
                request == null ? null : request.effectiveDate(),
                request == null ? null : request.amount(),
                request == null ? null : request.accountId(),
                request == null ? null : request.beneficiaryId(),
                request == null ? null : request.note());
        HttpHeaders headers = new HttpHeaders();
        if (result.replayed()) {
            headers.add("Idempotency-Replayed", "true");
        }
        if (result.resourceId() != null) {
            headers.setLocation(URI.create(
                    "/api/v1/households/" + householdId + "/incomes/" + result.resourceId()));
        }
        return ResponseEntity.status(result.httpStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.responseBody());
    }

    @PostMapping("/{incomeId}/void")
    ResponseEntity<String> voidIncome(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID incomeId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody VoidIncomeRequest request) {
        IncomeService.MutationResult result = incomes.voidIncome(
                actor,
                householdId,
                incomeId,
                idempotencyKey,
                ifMatch,
                request == null ? null : request.reason());
        HttpHeaders headers = new HttpHeaders();
        if (result.replayed()) {
            headers.add("Idempotency-Replayed", "true");
        }
        return ResponseEntity.status(result.httpStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.responseBody());
    }

    record CreateIncomeRequest(
            LocalDate effectiveDate, String amount, UUID accountId, UUID beneficiaryId, String note) {}

    record VoidIncomeRequest(String reason) {}
}
