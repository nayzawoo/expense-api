package com.nayzawoo.expense.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "account")
class Account {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "owner_label", nullable = false, length = 120)
    private String ownerLabel;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "opened_on", nullable = false)
    private LocalDate openedOn;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private long version;

    @Column(name = "balance_version", nullable = false)
    private long balanceVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {}

    static Account create(
            UUID id,
            UUID householdId,
            String name,
            String ownerLabel,
            String type,
            String currencyCode,
            LocalDate openedOn,
            int sortOrder,
            Instant now) {
        Account account = new Account();
        account.id = id;
        account.householdId = householdId;
        account.name = name;
        account.ownerLabel = ownerLabel;
        account.type = type;
        account.currencyCode = currencyCode;
        account.openedOn = openedOn;
        account.status = "ACTIVE";
        account.sortOrder = sortOrder;
        account.version = 0;
        account.balanceVersion = 1;
        account.createdAt = now;
        account.updatedAt = now;
        return account;
    }

    /** Metadata-only update. Opening date/amount and currency stay immutable. */
    void updateMetadata(String name, String ownerLabel, String type, Integer sortOrder, Instant now) {
        if (name != null) {
            this.name = name;
        }
        if (ownerLabel != null) {
            this.ownerLabel = ownerLabel;
        }
        if (type != null) {
            this.type = type;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
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

    String getOwnerLabel() {
        return ownerLabel;
    }

    String getType() {
        return type;
    }

    String getCurrencyCode() {
        return currencyCode;
    }

    LocalDate getOpenedOn() {
        return openedOn;
    }

    String getStatus() {
        return status;
    }

    int getSortOrder() {
        return sortOrder;
    }

    long getVersion() {
        return version;
    }

    long getBalanceVersion() {
        return balanceVersion;
    }
}
