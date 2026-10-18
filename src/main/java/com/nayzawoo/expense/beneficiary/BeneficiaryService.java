package com.nayzawoo.expense.beneficiary;

import com.nayzawoo.expense.identity.ExpenseUser;
import com.nayzawoo.expense.identity.FinancialAccess;
import com.nayzawoo.expense.identity.FinancialAccess.LockedHouseholdActor;
import com.nayzawoo.expense.shared.AuditEventWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaries;
    private final FinancialAccess financialAccess;
    private final AuditEventWriter audit;
    private final Clock clock;

    BeneficiaryService(
            BeneficiaryRepository beneficiaries,
            FinancialAccess financialAccess,
            AuditEventWriter audit,
            Clock clock) {
        this.beneficiaries = beneficiaries;
        this.financialAccess = financialAccess;
        this.audit = audit;
        this.clock = clock;
    }

    List<Beneficiary> list(ExpenseUser actor, UUID householdId, String status) {
        financialAccess.requireHouseholdMember(actor, householdId);
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return beneficiaries.findByHouseholdIdOrderBySortOrderAscNameAscIdAsc(householdId);
        }
        if (!isStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return beneficiaries.findByHouseholdIdAndStatusOrderBySortOrderAscNameAscIdAsc(householdId, status);
    }

    Beneficiary get(ExpenseUser actor, UUID householdId, UUID beneficiaryId) {
        financialAccess.requireHouseholdMember(actor, householdId);
        return beneficiaries
                .findByIdAndHouseholdId(beneficiaryId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    Beneficiary create(ExpenseUser actor, UUID householdId, String name, String kind, Integer sortOrder) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        if (!locked.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        String trimmedName = requireName(name);
        String normalizedKind = requireKind(kind);
        if (beneficiaries.existsNormalizedName(householdId, trimmedName, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        Beneficiary created = beneficiaries.save(Beneficiary.create(
                UUID.randomUUID(),
                householdId,
                trimmedName,
                normalizedKind,
                sortOrder == null ? 0 : sortOrder,
                clock.instant()));
        audit.record(
                householdId,
                locked.userId(),
                "BENEFICIARY_CREATED",
                "BENEFICIARY",
                created.getId(),
                "{\"name\":\""
                        + AuditEventWriter.escape(created.getName())
                        + "\",\"kind\":\""
                        + created.getKind()
                        + "\"}");
        return created;
    }

    @Transactional
    Beneficiary update(
            ExpenseUser actor, UUID householdId, UUID beneficiaryId, String name, String kind, Integer sortOrder) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        if (!locked.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (name == null && kind == null && sortOrder == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        Beneficiary beneficiary = beneficiaries
                .findByIdAndHouseholdId(beneficiaryId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String previousName = beneficiary.getName();
        String nextName = name == null ? null : requireName(name);
        String nextKind = kind == null ? null : requireKind(kind);
        if (nextName != null
                && !normalize(nextName).equals(normalize(previousName))
                && beneficiaries.existsNormalizedName(householdId, nextName, beneficiaryId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        beneficiary.update(nextName, nextKind, sortOrder, clock.instant());
        Beneficiary saved = beneficiaries.save(beneficiary);
        if (nextName != null && !normalize(nextName).equals(normalize(previousName))) {
            audit.record(
                    householdId,
                    locked.userId(),
                    "BENEFICIARY_RENAMED",
                    "BENEFICIARY",
                    saved.getId(),
                    "{\"previousName\":\""
                            + AuditEventWriter.escape(previousName)
                            + "\",\"newName\":\""
                            + AuditEventWriter.escape(saved.getName())
                            + "\"}");
        }
        return saved;
    }

    @Transactional
    Beneficiary updateStatus(ExpenseUser actor, UUID householdId, UUID beneficiaryId, String status) {
        LockedHouseholdActor locked = financialAccess.lockForFinancialWrite(actor, householdId);
        if (!locked.admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (!isStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        Beneficiary beneficiary = beneficiaries
                .findByIdAndHouseholdId(beneficiaryId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String previous = beneficiary.getStatus();
        beneficiary.updateStatus(status, clock.instant());
        Beneficiary saved = beneficiaries.save(beneficiary);
        if (!previous.equals(status)) {
            audit.record(
                    householdId,
                    locked.userId(),
                    "BENEFICIARY_STATUS_CHANGED",
                    "BENEFICIARY",
                    saved.getId(),
                    "{\"previousStatus\":\""
                            + previous
                            + "\",\"newStatus\":\""
                            + status
                            + "\"}");
        }
        return saved;
    }

    @Transactional
    public void seedDefaults(UUID householdId) {
        Instant now = clock.instant();
        if (!beneficiaries.existsNormalizedName(householdId, "Household", null)) {
            beneficiaries.save(Beneficiary.create(
                    UUID.randomUUID(), householdId, "Household", "HOUSEHOLD", 0, now));
        }
        if (!beneficiaries.existsNormalizedName(householdId, "Other", null)) {
            beneficiaries.save(Beneficiary.create(UUID.randomUUID(), householdId, "Other", "OTHER", 1, now));
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return name.trim();
    }

    private static String requireKind(String kind) {
        if (!"PERSON".equals(kind) && !"HOUSEHOLD".equals(kind) && !"OTHER".equals(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return kind;
    }

    private static boolean isStatus(String status) {
        return "ACTIVE".equals(status) || "ARCHIVED".equals(status);
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
