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

    static AppUser createBootstrapAdmin(
            UUID id, UUID householdId, String email, String displayName, String passwordHash) {
        return provision(id, householdId, email, displayName, passwordHash, "ADMIN");
    }

    static AppUser provision(
            UUID id, UUID householdId, String email, String displayName, String passwordHash, String role) {
        AppUser user = new AppUser();
        user.id = id;
        user.householdId = householdId;
        user.email = email;
        user.displayName = displayName;
        user.passwordHash = passwordHash;
        user.role = role;
        user.status = "ACTIVE";
        user.mustChangePassword = true;
        user.passwordChangedAt = null;
        user.securityVersion = 0;
        return user;
    }

    void rotatePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = false;
        if (this.passwordChangedAt == null) {
            this.passwordChangedAt = now;
        }
        this.securityVersion += 1;
    }

    void updateDisplayName(String displayName) {
        this.displayName = displayName;
    }

    void updateRole(String role) {
        this.role = role;
    }

    void updateStatus(String status) {
        this.status = status;
    }

    void changeEmail(String email) {
        this.email = email;
        this.securityVersion += 1;
    }

    void assignTemporaryPassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = true;
        this.securityVersion += 1;
    }

    Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

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
