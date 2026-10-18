package com.nayzawoo.expense.identity;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinancialAccess {

    private final JdbcTemplate jdbc;
    private final IdentityLocks locks;

    FinancialAccess(JdbcTemplate jdbc, IdentityLocks locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    public LockedHouseholdActor lockForFinancialWrite(ExpenseUser actor, UUID householdId) {
        if (actor == null || householdId == null || !householdId.equals(actor.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        locks.lockHouseholdShare(householdId);
        LockedUserRow user = lockActorRow(actor.getId());
        if (!"ACTIVE".equals(user.status())
                || user.mustChangePassword()
                || user.securityVersion() != actor.getSecurityVersion()
                || !householdId.equals(user.householdId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        HouseholdRow household = readHousehold(householdId);
        return new LockedHouseholdActor(
                householdId, user.id(), user.role(), user.securityVersion(), household.currency(), household.timezone());
    }

    public LockedHouseholdActor requireHouseholdMember(ExpenseUser actor, UUID householdId) {
        if (actor == null || householdId == null || !householdId.equals(actor.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Integer exists = jdbc.query(
                "SELECT 1 FROM household WHERE id = ?", rs -> rs.next() ? 1 : null, householdId);
        if (exists == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        LockedUserRow user = readActorRow(actor.getId());
        if (!"ACTIVE".equals(user.status()) || user.mustChangePassword() || !householdId.equals(user.householdId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        HouseholdRow household = readHousehold(householdId);
        return new LockedHouseholdActor(
                householdId, user.id(), user.role(), user.securityVersion(), household.currency(), household.timezone());
    }

    public record LockedHouseholdActor(
            UUID householdId, UUID userId, String role, long securityVersion, String currency, String timezone) {

        public boolean admin() {
            return "ADMIN".equals(role);
        }
    }

    private LockedUserRow lockActorRow(UUID userId) {
        LockedUserRow user = jdbc.query(
                """
                SELECT id, household_id, role, status, must_change_password, security_version
                FROM app_user
                WHERE id = ?
                FOR UPDATE
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new LockedUserRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("household_id", UUID.class),
                            rs.getString("role"),
                            rs.getString("status"),
                            rs.getBoolean("must_change_password"),
                            rs.getLong("security_version"));
                },
                userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    private LockedUserRow readActorRow(UUID userId) {
        LockedUserRow user = jdbc.query(
                """
                SELECT id, household_id, role, status, must_change_password, security_version
                FROM app_user
                WHERE id = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new LockedUserRow(
                            rs.getObject("id", UUID.class),
                            rs.getObject("household_id", UUID.class),
                            rs.getString("role"),
                            rs.getString("status"),
                            rs.getBoolean("must_change_password"),
                            rs.getLong("security_version"));
                },
                userId);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return user;
    }

    private HouseholdRow readHousehold(UUID householdId) {
        HouseholdRow household = jdbc.query(
                "SELECT currency, timezone FROM household WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new HouseholdRow(rs.getString("currency"), rs.getString("timezone"));
                },
                householdId);
        if (household == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return household;
    }

    private record LockedUserRow(
            UUID id, UUID householdId, String role, String status, boolean mustChangePassword, long securityVersion) {}

    private record HouseholdRow(String currency, String timezone) {}
}
