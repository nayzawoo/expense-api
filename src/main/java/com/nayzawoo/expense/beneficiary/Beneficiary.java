package com.nayzawoo.expense.beneficiary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "beneficiary")
class Beneficiary {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Beneficiary() {}

    static Beneficiary create(
            UUID id, UUID householdId, String name, String kind, int sortOrder, Instant now) {
        Beneficiary beneficiary = new Beneficiary();
        beneficiary.id = id;
        beneficiary.householdId = householdId;
        beneficiary.name = name;
        beneficiary.kind = kind;
        beneficiary.sortOrder = sortOrder;
        beneficiary.status = "ACTIVE";
        beneficiary.version = 0;
        beneficiary.createdAt = now;
        beneficiary.updatedAt = now;
        return beneficiary;
    }

    void update(String name, String kind, Integer sortOrder, Instant now) {
        if (name != null) {
            this.name = name;
        }
        if (kind != null) {
            this.kind = kind;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
        this.version++;
        this.updatedAt = now;
    }

    void updateStatus(String status, Instant now) {
        this.status = status;
        this.version++;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getHouseholdId() {
        return householdId;
    }

    String getName() {
        return name;
    }

    String getKind() {
        return kind;
    }

    int getSortOrder() {
        return sortOrder;
    }

    String getStatus() {
        return status;
    }

    long getVersion() {
        return version;
    }
}
