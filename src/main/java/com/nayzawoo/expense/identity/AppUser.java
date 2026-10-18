package com.nayzawoo.expense.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
class AppUser {

    @Id
    private UUID id;

    @Column(name = "household_id", nullable = false)
    private UUID householdId;

    @Column(nullable = false)
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String status;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "security_version", nullable = false)
    private long securityVersion;

    protected AppUser() {}

    UUID getId() {
        return id;
    }

    UUID getHouseholdId() {
        return householdId;
    }

    String getEmail() {
        return email;
    }

    String getDisplayName() {
        return displayName;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    String getRole() {
        return role;
    }

    String getStatus() {
        return status;
    }

    boolean isMustChangePassword() {
        return mustChangePassword;
    }

    long getSecurityVersion() {
        return securityVersion;
    }
}
