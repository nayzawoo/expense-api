package com.nayzawoo.expense.account;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/{householdId}/accounts")
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    List<AccountResponse> list(@AuthenticationPrincipal ExpenseUser actor, @PathVariable UUID householdId) {
        return accounts.list(actor, householdId).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{accountId}")
    AccountResponse detail(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID accountId) {
        return AccountResponse.from(accounts.get(actor, householdId, accountId));
    }

    @GetMapping("/{accountId}/ledger")
    LedgerResponse ledger(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID accountId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return LedgerResponse.from(accounts.ledger(actor, householdId, accountId, from, to, page, size));
    }

    @PostMapping
    ResponseEntity<String> create(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateAccountRequest request) {
        AccountService.CreateResult result = accounts.create(
                actor,
                householdId,
                idempotencyKey,
                request == null ? null : request.name(),
                request == null ? null : request.type(),
                request == null ? null : request.ownerLabel(),
                request == null ? null : request.openedOn(),
                request == null ? null : request.openingAmount(),
                request == null ? null : request.sortOrder());
        HttpHeaders headers = new HttpHeaders();
        if (result.replayed()) {
            headers.add("Idempotency-Replayed", "true");
        }
        if (result.resourceId() != null) {
            headers.setLocation(URI.create(
                    "/api/v1/households/" + householdId + "/accounts/" + result.resourceId()));
        }
        return ResponseEntity.status(result.httpStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.responseBody());
    }

    record CreateAccountRequest(
            String name, String type, String ownerLabel, LocalDate openedOn, String openingAmount, Integer sortOrder) {}

    record LedgerResponse(
            String accountId,
            String currency,
            String from,
            String to,
            String openingBalance,
            String closingBalance,
            int page,
            int size,
            long totalElements,
            int totalPages,
            List<LedgerEntryResponse> items) {

        static LedgerResponse from(AccountService.LedgerView view) {
            return new LedgerResponse(
                    view.accountId().toString(),
                    view.currency(),
                    view.from().toString(),
                    view.to().toString(),
                    view.openingBalance(),
                    view.closingBalance(),
                    view.page(),
                    view.size(),
                    view.totalElements(),
                    view.totalPages(),
                    view.items().stream()
                            .map(entry -> new LedgerEntryResponse(
                                    entry.postingId().toString(),
                                    entry.transactionId().toString(),
                                    entry.kind(),
                                    entry.role(),
                                    entry.signedAmount(),
                                    entry.effectiveDate().toString(),
                                    entry.recordedAt().toString(),
                                    entry.revisionNo(),
                                    new ActorResponse(
                                            entry.actor().userId().toString(), entry.actor().displayName()),
                                    entry.runningBalance()))
                            .toList());
        }
    }

    record LedgerEntryResponse(
            String postingId,
            String transactionId,
            String kind,
            String role,
            String signedAmount,
            String effectiveDate,
            String recordedAt,
            int revisionNo,
            ActorResponse actor,
            String runningBalance) {}

    record ActorResponse(String userId, String displayName) {}

    record AccountResponse(
            String id,
            String name,
            String type,
            String ownerLabel,
            String openedOn,
            String openingAmount,
            String currentBalance,
            String balanceAsOf,
            long balanceVersion,
            boolean negativeBalanceWarning,
            String status,
            int sortOrder,
            long version,
            String currency) {

        static AccountResponse from(AccountService.AccountView view) {
            return new AccountResponse(
                    view.id().toString(),
                    view.name(),
                    view.type(),
                    view.ownerLabel(),
                    view.openedOn().toString(),
                    view.openingAmount(),
                    view.currentBalance(),
                    view.balanceAsOf().toString(),
                    view.balanceVersion(),
                    view.negativeBalanceWarning(),
                    view.status(),
                    view.sortOrder(),
                    view.version(),
                    view.currency());
        }
    }
}
