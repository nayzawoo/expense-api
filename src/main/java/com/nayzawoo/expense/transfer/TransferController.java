package com.nayzawoo.expense.transfer;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.transfer.TransferService.TransferResponse;
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
@RequestMapping("/api/v1/households/{householdId}/transfers")
class TransferController {

    private final TransferService transfers;

    TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @GetMapping
    List<TransferResponse> list(@AuthenticationPrincipal ExpenseUser actor, @PathVariable UUID householdId) {
        return transfers.list(actor, householdId).stream().map(TransferService::toResponse).toList();
    }

    @GetMapping("/{transferId}")
    TransferResponse detail(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID transferId) {
        return TransferService.toResponse(transfers.get(actor, householdId, transferId));
    }

    @PostMapping
    ResponseEntity<String> create(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateTransferRequest request) {
        TransferService.MutationResult result = transfers.create(
                actor,
                householdId,
                idempotencyKey,
                request == null ? null : request.effectiveDate(),
                request == null ? null : request.amount(),
                request == null ? null : request.sourceAccountId(),
                request == null ? null : request.destinationAccountId(),
                request == null ? null : request.note());
        HttpHeaders headers = new HttpHeaders();
        if (result.replayed()) {
            headers.add("Idempotency-Replayed", "true");
        }
        if (result.resourceId() != null) {
            headers.setLocation(URI.create(
                    "/api/v1/households/" + householdId + "/transfers/" + result.resourceId()));
        }
        return ResponseEntity.status(result.httpStatus())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(result.responseBody());
    }

    @PostMapping("/{transferId}/void")
    ResponseEntity<String> voidTransfer(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID transferId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody VoidTransferRequest request) {
        TransferService.MutationResult result = transfers.voidTransfer(
                actor,
                householdId,
                transferId,
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

    record CreateTransferRequest(
            LocalDate effectiveDate,
            String amount,
            UUID sourceAccountId,
            UUID destinationAccountId,
            String note) {}

    record VoidTransferRequest(String reason) {}
}
