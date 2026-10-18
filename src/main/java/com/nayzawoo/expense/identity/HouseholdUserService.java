package com.nayzawoo.expense.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class HouseholdUserService {

    private final HouseholdRepository households;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevocationService sessionRevocation;
    private final IdentityAuditService audit;
    private final IdentityLocks locks;

    HouseholdUserService(
            HouseholdRepository households,
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            SessionRevocationService sessionRevocation,
            IdentityAuditService audit,
            IdentityLocks locks) {
        this.households = households;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevocation = sessionRevocation;
        this.audit = audit;
        this.locks = locks;
    }

    List<AppUser> listUsers(ExpenseUser actor, UUID householdId) {
        requireAdminInHousehold(actor, householdId);
        return users.findByHouseholdIdOrderByDisplayNameAscEmailAsc(householdId);
    }

    AppUser getUser(ExpenseUser actor, UUID householdId, UUID userId) {
        requireAdminInHousehold(actor, householdId);
        return users.findByIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @Transactional
    AppUser createUser(
            ExpenseUser actor,
            UUID householdId,
            String displayName,
            String email,
            String temporaryPassword,
            String role) {
        locks.lockHouseholdExclusive(householdId);
        locks.requireActiveAdmin(actor, householdId);
        if (displayName == null
                || displayName.isBlank()
                || email == null
                || email.isBlank()
                || temporaryPassword == null
                || temporaryPassword.isBlank()
                || temporaryPassword.length() < PasswordChangeService.MINIMUM_PASSWORD_LENGTH
                || !isRole(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String normalizedEmail = AppUserDetailsService.normalize(email);
        if (users.existsByEmail(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return users.save(AppUser.provision(
                UUID.randomUUID(),
                householdId,
                normalizedEmail,
                displayName.trim(),
                passwordEncoder.encode(temporaryPassword),
                role));
    }

    @Transactional
    AppUser updateUser(
            ExpenseUser actor,
            UUID householdId,
            UUID userId,
            String displayName,
            String role,
            String status,
            String email) {
        boolean mutatesAuthority = role != null || status != null;
        if (mutatesAuthority) {
            locks.lockHouseholdExclusive(householdId);
        } else {
            locks.lockHouseholdShare(householdId);
        }
        locks.requireActiveAdmin(actor, householdId);
        locks.lockUsersInOrder(householdId, actor.getId(), userId);
        AppUser target = users.findByIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        boolean self = actor.getId().equals(target.getId());
        String nextRole = role == null ? target.getRole() : role;
        String nextStatus = status == null ? target.getStatus() : status;
        if (role != null) {
            if (self || !isRole(role)) {
                throw new ResponseStatusException(self ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST);
            }
        }
        if (status != null) {
            if (self || !isStatus(status)) {
                throw new ResponseStatusException(self ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST);
            }
        }
        if (role != null || status != null) {
            guardLastActiveAdmin(householdId, target, nextRole, nextStatus);
        }
        boolean disabling = status != null
                && "DISABLED".equals(status)
                && !"DISABLED".equals(target.getStatus());
        String previousEmail = target.getEmail();
        if (role != null) {
            target.updateRole(role);
        }
        if (status != null) {
            target.updateStatus(status);
        }
        if (displayName != null) {
            if (displayName.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            target.updateDisplayName(displayName.trim());
        }
        boolean emailChanged = false;
        if (email != null) {
            if (self) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            if (email.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            String normalizedEmail = AppUserDetailsService.normalize(email);
            if (!normalizedEmail.equals(target.getEmail()) && users.existsByEmail(normalizedEmail)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            if (!normalizedEmail.equals(previousEmail)) {
                target.changeEmail(normalizedEmail);
                emailChanged = true;
            }
        }
        users.save(target);
        if (emailChanged) {
            audit.record(
                    householdId,
                    actor.getId(),
                    IdentityAuditService.EVENT_LOGIN_EMAIL_CHANGED,
                    IdentityAuditService.RESOURCE_USER,
                    target.getId(),
                    IdentityAuditService.emailChangeDetails(target.getId(), previousEmail, target.getEmail()));
        }
        if (disabling || emailChanged) {
            sessionRevocation.revokeAllForEmail(previousEmail);
        }
        return target;
    }

    @Transactional
    void assignTemporaryPassword(ExpenseUser actor, UUID householdId, UUID userId, String temporaryPassword) {
        locks.lockHouseholdExclusive(householdId);
        locks.requireActiveAdmin(actor, householdId);
        locks.lockUsersInOrder(householdId, actor.getId(), userId);
        if (temporaryPassword == null
                || temporaryPassword.isBlank()
                || temporaryPassword.length() < PasswordChangeService.MINIMUM_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        AppUser target = users.findByIdAndHouseholdId(userId, householdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (actor.getId().equals(target.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        target.assignTemporaryPassword(passwordEncoder.encode(temporaryPassword));
        users.save(target);
        audit.record(
                householdId,
                actor.getId(),
                IdentityAuditService.EVENT_TEMPORARY_PASSWORD_ASSIGNED,
                IdentityAuditService.RESOURCE_USER,
                target.getId(),
                IdentityAuditService.targetUserDetails(target.getId()));
        sessionRevocation.revokeAllForEmail(target.getEmail());
    }

    private void requireAdminInHousehold(ExpenseUser actor, UUID householdId) {
        if (!households.existsById(householdId) || !actor.getHouseholdId().equals(householdId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!"ADMIN".equals(actor.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    private void guardLastActiveAdmin(UUID householdId, AppUser target, String nextRole, String nextStatus) {
        boolean currentlyActiveAdmin = "ADMIN".equals(target.getRole()) && "ACTIVE".equals(target.getStatus());
        boolean remainsActiveAdmin = "ADMIN".equals(nextRole) && "ACTIVE".equals(nextStatus);
        if (currentlyActiveAdmin
                && !remainsActiveAdmin
                && users.countByHouseholdIdAndRoleAndStatus(householdId, "ADMIN", "ACTIVE") <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
    }

    private static boolean isRole(String role) {
        return "ADMIN".equals(role) || "MEMBER".equals(role);
    }

    private static boolean isStatus(String status) {
        return "ACTIVE".equals(status) || "DISABLED".equals(status);
    }
}
