package com.nayzawoo.expense.identity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class ExpenseUser implements UserDetails {

    private final UUID id;
    private final UUID householdId;
    private final String email;
    private final String displayName;
    private final String passwordHash;
    private final String role;
    private final boolean active;
    private final boolean mustChangePassword;
    private final long securityVersion;

    ExpenseUser(AppUser user) {
        this.id = user.getId();
        this.householdId = user.getHouseholdId();
        this.email = user.getEmail();
        this.displayName = user.getDisplayName();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
        this.active = "ACTIVE".equals(user.getStatus());
        this.mustChangePassword = user.isMustChangePassword();
        this.securityVersion = user.getSecurityVersion();
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRole() {
        return role;
    }

    public boolean mustChangePassword() {
        return mustChangePassword;
    }

    public long getSecurityVersion() {
        return securityVersion;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
