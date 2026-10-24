package com.nayzawoo.expense.identity;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class RecoveryService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevocationService sessionRevocation;
    private final IdentityAuditService audit;

    RecoveryService(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            SessionRevocationService sessionRevocation,
            IdentityAuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevocation = sessionRevocation;
        this.audit = audit;
    }

    @Transactional
    RecoveryResult recover(String adminEmail, String adminPassword) {
        if (adminEmail == null
                || adminEmail.isBlank()
                || adminPassword == null
                || adminPassword.isBlank()
                || adminPassword.length() < PasswordChangeService.MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Recovery requires an admin email and password");
        }
        String email = AppUserDetailsService.normalize(adminEmail);
        AppUser user = users.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Admin user not found"));
        if (!"ADMIN".equals(user.getRole()) || !"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Recovery target must be an active admin");
        }
        resetPasswordForUser(user, adminPassword);
        return new RecoveryResult(user.getId(), user.getEmail(), user.getSecurityVersion());
    }

    @Transactional
    void resetPassword(String userEmail, String password) {
        if (userEmail == null || userEmail.isBlank() || password == null || password.isBlank()
                || password.length() < PasswordChangeService.MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("An email and password of at least "
                    + PasswordChangeService.MINIMUM_PASSWORD_LENGTH + " characters are required");
        }
        AppUser user = users.findByEmail(AppUserDetailsService.normalize(userEmail))
                .orElseThrow(() -> new IllegalStateException("User not found"));
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("Password reset target must be active");
        }
        resetPasswordForUser(user, password);
    }

    private void resetPasswordForUser(AppUser user, String password) {
        user.recoverAccess(passwordEncoder.encode(password));
        users.save(user);
        audit.record(
                user.getHouseholdId(),
                null,
                IdentityAuditService.EVENT_OPERATOR_RECOVERY,
                IdentityAuditService.RESOURCE_USER,
                user.getId(),
                IdentityAuditService.targetUserDetails(user.getId()));
        sessionRevocation.revokeAllForEmail(user.getEmail());
    }

    record RecoveryResult(java.util.UUID userId, String adminEmail, long securityVersion) {}
}
