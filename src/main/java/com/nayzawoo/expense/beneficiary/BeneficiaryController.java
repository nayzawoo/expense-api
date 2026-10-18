package com.nayzawoo.expense.beneficiary;

import com.nayzawoo.expense.identity.ExpenseUser;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/households/{householdId}/beneficiaries")
class BeneficiaryController {

    private final BeneficiaryService beneficiaries;

    BeneficiaryController(BeneficiaryService beneficiaries) {
        this.beneficiaries = beneficiaries;
    }

    @GetMapping
    List<BeneficiaryResponse> list(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestParam(required = false) String status) {
        return beneficiaries.list(actor, householdId, status).stream()
                .map(BeneficiaryResponse::from)
                .toList();
    }

    @GetMapping("/{beneficiaryId}")
    BeneficiaryResponse detail(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID beneficiaryId) {
        return BeneficiaryResponse.from(beneficiaries.get(actor, householdId, beneficiaryId));
    }

    @PostMapping
    ResponseEntity<BeneficiaryResponse> create(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @RequestBody CreateBeneficiaryRequest request) {
        Beneficiary created = beneficiaries.create(
                actor,
                householdId,
                request == null ? null : request.name(),
                request == null ? null : request.kind(),
                request == null ? null : request.sortOrder());
        return ResponseEntity.created(URI.create(
                        "/api/v1/households/" + householdId + "/beneficiaries/" + created.getId()))
                .body(BeneficiaryResponse.from(created));
    }

    @PutMapping("/{beneficiaryId}")
    BeneficiaryResponse update(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID beneficiaryId,
            @RequestBody UpdateBeneficiaryRequest request) {
        return BeneficiaryResponse.from(beneficiaries.update(
                actor,
                householdId,
                beneficiaryId,
                request == null ? null : request.name(),
                request == null ? null : request.kind(),
                request == null ? null : request.sortOrder()));
    }

    @PatchMapping("/{beneficiaryId}/status")
    BeneficiaryResponse updateStatus(
            @AuthenticationPrincipal ExpenseUser actor,
            @PathVariable UUID householdId,
            @PathVariable UUID beneficiaryId,
            @RequestBody StatusRequest request) {
        return BeneficiaryResponse.from(beneficiaries.updateStatus(
                actor, householdId, beneficiaryId, request == null ? null : request.status()));
    }

    record CreateBeneficiaryRequest(String name, String kind, Integer sortOrder) {}

    record UpdateBeneficiaryRequest(String name, String kind, Integer sortOrder) {}

    record StatusRequest(String status) {}

    record BeneficiaryResponse(
            String id, String name, String kind, int sortOrder, String status, long version) {

        static BeneficiaryResponse from(Beneficiary beneficiary) {
            return new BeneficiaryResponse(
                    beneficiary.getId().toString(),
                    beneficiary.getName(),
                    beneficiary.getKind(),
                    beneficiary.getSortOrder(),
                    beneficiary.getStatus(),
                    beneficiary.getVersion());
        }
    }
}
