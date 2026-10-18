package com.nayzawoo.expense.reconciliation;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.reconciliation.ReconciliationService.AdjustmentResponse;
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
@RequestMapping("/api/v1/households/{householdId}/adjustments")
class AdjustmentController {

    private final ReconciliationService reconciliations;

    AdjustmentController(ReconciliationService reconciliations) {
        this.reconciliations = reconciliations;
    }

    @GetMapping
    List<AdjustmentResponse> list(@AuthenticationPrincipal ExpenseUser actor, @PathVariable UUID householdId) {
        return reconciliations.list(actor, householdId).stream()
                .map(ReconciliationService::toResponse)
                .toList();
    }

    @GetMapping("/{adjustmentId}")
    AdjustmentResponse detail(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID adjustmentId) {
        return ReconciliationService.toResponse(reconciliations.get(actor, householdId, adjustmentId));
    }

    @PostMapping("/{adjustmentId}/void")
    ResponseEntity<String> voidAdjustment(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID adjustmentId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody VoidAdjustmentRequest request) {
        ReconciliationService.MutationResult result = reconciliations.voidAdjustment(
                actor,
                householdId,
                adjustmentId,
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

    record VoidAdjustmentRequest(String reason) {}
}
