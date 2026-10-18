package com.nayzawoo.expense.identity;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class ProfileService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionRevocationService sessionRevocation;

    ProfileService(
            AppUserRepository users, PasswordEncoder passwordEncoder, SessionRevocationService sessionRevocation) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessionRevocation = sessionRevocation;
    }

    AppUser getProfile(ExpenseUser actor) {
        return users.findById(actor.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    @Transactional
    ProfileUpdateResult updateProfile(ExpenseUser actor, String displayName, String email, String currentPassword) {
        AppUser user =
                users.findById(actor.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (user.isMustChangePassword()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (displayName == null && email == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (displayName != null) {
            if (displayName.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            user.updateDisplayName(displayName.trim());
        }
        boolean emailChanged = false;
        String previousEmail = user.getEmail();
        if (email != null) {
            if (email.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            }
            String normalizedEmail = AppUserDetailsService.normalize(email);
            if (!normalizedEmail.equals(user.getEmail())) {
                if (currentPassword == null
                        || currentPassword.isBlank()
                        || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
                }
                if (users.existsByEmail(normalizedEmail)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT);
                }
                user.changeEmail(normalizedEmail);
                emailChanged = true;
            }
        }
        users.save(user);
        if (emailChanged) {
            sessionRevocation.revokeAllForEmail(previousEmail);
        }
        return new ProfileUpdateResult(user, emailChanged);
    }

    record ProfileUpdateResult(AppUser user, boolean sessionsRevoked) {}
}
