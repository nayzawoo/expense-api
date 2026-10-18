package com.nayzawoo.expense.identity;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class PasswordChangeService {

    static final int MINIMUM_PASSWORD_LENGTH = 8;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final Clock clock;

    PasswordChangeService(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            FindByIndexNameSessionRepository<? extends Session> sessions,
            Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    void changePassword(UUID userId, String currentPassword, String newPassword) {
        if (currentPassword == null
                || currentPassword.isBlank()
                || newPassword == null
                || newPassword.isBlank()
                || newPassword.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        user.rotatePassword(passwordEncoder.encode(newPassword), clock.instant());
        users.save(user);
        revokeSessions(user.getEmail());
    }

    private void revokeSessions(String email) {
        Map<String, ? extends Session> matching =
                sessions.findByIndexNameAndIndexValue(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, email);
        for (String sessionId : matching.keySet()) {
            sessions.deleteById(sessionId);
        }
    }
}
