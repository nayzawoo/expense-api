package com.nayzawoo.expense.identity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class IdentityLocks {

    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final AppUserRepository users;

    IdentityLocks(JdbcTemplate jdbc, EntityManager entityManager, AppUserRepository users) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.users = users;
    }

    void lockHouseholdShare(UUID householdId) {
        Integer locked = jdbc.query(
                "SELECT 1 FROM household WHERE id = ? FOR SHARE", rs -> rs.next() ? 1 : null, householdId);
        if (locked == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    void lockHouseholdExclusive(UUID householdId) {
        Integer locked = jdbc.query(
                "SELECT 1 FROM household WHERE id = ? FOR UPDATE", rs -> rs.next() ? 1 : null, householdId);
        if (locked == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    AppUser lockUser(UUID userId) {
        Integer locked = jdbc.query(
                "SELECT 1 FROM app_user WHERE id = ? FOR UPDATE", rs -> rs.next() ? 1 : null, userId);
        if (locked == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        AppUser user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        return user;
    }

    AppUser lockUserInHousehold(UUID userId, UUID householdId) {
        AppUser user = lockUser(userId);
        if (!householdId.equals(user.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return user;
    }

    List<AppUser> lockUsersInOrder(UUID householdId, UUID... userIds) {
        List<UUID> ordered = new ArrayList<>();
        for (UUID userId : userIds) {
            if (userId != null && !ordered.contains(userId)) {
                ordered.add(userId);
            }
        }
        ordered.sort(Comparator.naturalOrder());
        List<AppUser> locked = new ArrayList<>(ordered.size());
        for (UUID userId : ordered) {
            locked.add(lockUserInHousehold(userId, householdId));
        }
        return locked;
    }

    AppUser requireActiveAdmin(ExpenseUser actor, UUID householdId) {
        if (actor == null || householdId == null || !householdId.equals(actor.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        AppUser user = lockUserInHousehold(actor.getId(), householdId);
        if (!"ADMIN".equals(user.getRole())
                || !"ACTIVE".equals(user.getStatus())
                || user.isMustChangePassword()
                || user.getSecurityVersion() != actor.getSecurityVersion()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return user;
    }
}
