package com.nayzawoo.expense.reconciliation;

import com.nayzawoo.expense.identity.ExpenseUser;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/{householdId}/accounts/{accountId}/reconciliations")
class ReconciliationController {

    private final ReconciliationService reconciliations;

    ReconciliationController(ReconciliationService reconciliations) {
        this.reconciliations = reconciliations;
    }

    @PostMapping
    ResponseEntity<String> create(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody ReconcileRequest request) {
        ReconciliationService.MutationResult result = reconciliations.reconcile(
                actor,
                householdId,
                accountId,
                idempotencyKey,
                request == null ? null : request.actualBalance(),
                request == null ? null : request.expectedBalanceVersion(),
                request == null ? null : request.reason());
        HttpHeaders headers = new HttpHeaders();
        if (result.replayed()) {
            headers.add("Idempotency-Replayed", "true");
        }
        if (result.resourceId() != null) {
            headers.setLocation(URI.create(
                    "/api/v1/households/" + householdId + "/adjustments/" + result.resourceId()));
        }
        return ResponseEntity.status(result.httpStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.responseBody());
    }

    record ReconcileRequest(String actualBalance, Long expectedBalanceVersion, String reason) {}
}
