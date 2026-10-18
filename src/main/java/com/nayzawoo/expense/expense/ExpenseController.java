package com.nayzawoo.expense.expense;

import com.nayzawoo.expense.expense.ExpenseService.ExpenseResponse;
import com.nayzawoo.expense.identity.ExpenseUser;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/{householdId}/expenses")
class ExpenseController {

    private final ExpenseService expenses;

    ExpenseController(ExpenseService expenses) {
        this.expenses = expenses;
    }

    @GetMapping
    List<ExpenseResponse> list(@AuthenticationPrincipal ExpenseUser actor, @PathVariable UUID householdId) {
        return expenses.list(actor, householdId).stream().map(ExpenseService::toResponse).toList();
    }

    @GetMapping("/{expenseId}")
    ExpenseResponse detail(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID expenseId) {
        return ExpenseService.toResponse(expenses.get(actor, householdId, expenseId));
    }

    @PostMapping
    ResponseEntity<String> create(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateExpenseRequest request) {
        ExpenseService.CreateResult result = expenses.create(
                actor,
                householdId,
                idempotencyKey,
                request == null ? null : request.effectiveDate(),
                request == null ? null : request.amount(),
                request == null ? null : request.accountId(),
                request == null ? null : request.categoryId(),
                request == null ? null : request.beneficiaryId(),
                request == null ? null : request.note());
        HttpHeaders headers = new HttpHeaders();
        if (result.replayed()) {
            headers.add("Idempotency-Replayed", "true");
        }
        if (result.resourceId() != null) {
            headers.setLocation(URI.create(
                    "/api/v1/households/" + householdId + "/expenses/" + result.resourceId()));
        }
        return ResponseEntity.status(result.httpStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.responseBody());
    }

    @PutMapping("/{expenseId}")
    ResponseEntity<String> replace(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID expenseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody ReplaceExpenseRequest request) {
        ExpenseService.CreateResult result = expenses.replace(
                actor,
                householdId,
                expenseId,
                idempotencyKey,
                ifMatch,
                request == null ? null : request.effectiveDate(),
                request == null ? null : request.amount(),
                request == null ? null : request.accountId(),
                request == null ? null : request.categoryId(),
                request == null ? null : request.beneficiaryId(),
                request == null ? null : request.note(),
                request == null ? null : request.changeReason());
        return mutationResponse(result);
    }

    @PostMapping("/{expenseId}/void")
    ResponseEntity<String> voidExpense(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID expenseId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody VoidExpenseRequest request) {
        ExpenseService.CreateResult result = expenses.voidExpense(
                actor,
                householdId,
                expenseId,
                idempotencyKey,
                ifMatch,
                request == null ? null : request.reason());
        return mutationResponse(result);
    }

    private static ResponseEntity<String> mutationResponse(ExpenseService.CreateResult result) {
        HttpHeaders headers = new HttpHeaders();
        if (result.replayed()) {
            headers.add("Idempotency-Replayed", "true");
        }
        return ResponseEntity.status(result.httpStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.responseBody());
    }

    record CreateExpenseRequest(
            LocalDate effectiveDate,
            String amount,
            UUID accountId,
            UUID categoryId,
            UUID beneficiaryId,
            String note) {}

    record ReplaceExpenseRequest(
            LocalDate effectiveDate,
            String amount,
            UUID accountId,
            UUID categoryId,
            UUID beneficiaryId,
            String note,
            String changeReason) {}

    record VoidExpenseRequest(String reason) {}
}
