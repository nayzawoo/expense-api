package com.nayzawoo.expense.identity;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinancialAccess {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;

    FinancialAccess(JdbcTemplate jdbc, AppUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public LockedHouseholdActor lockForFinancialWrite(ExpenseUser actor, UUID householdId) {
        if (actor == null || householdId == null || !householdId.equals(actor.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Integer householdLocked = jdbc.query(
                "SELECT 1 FROM household WHERE id = ? FOR SHARE",
                rs -> rs.next() ? 1 : null,
                householdId);
        if (householdLocked == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        AppUser user = users.findById(actor.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        jdbc.query("SELECT 1 FROM app_user WHERE id = ? FOR UPDATE", rs -> rs.next() ? 1 : null, actor.getId());
        user = users.findById(actor.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!"ACTIVE".equals(user.getStatus())
                || user.isMustChangePassword()
                || user.getSecurityVersion() != actor.getSecurityVersion()
                || !householdId.equals(user.getHouseholdId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
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
        return new LockedHouseholdActor(
                householdId, user.getId(), user.getRole(), user.getSecurityVersion(), household.currency(), household.timezone());
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
        AppUser user = users.findById(actor.getId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!"ACTIVE".equals(user.getStatus()) || user.isMustChangePassword()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        HouseholdRow household = jdbc.query(
                "SELECT currency, timezone FROM household WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    return new HouseholdRow(rs.getString("currency"), rs.getString("timezone"));
                },
                householdId);
        return new LockedHouseholdActor(
                householdId, user.getId(), user.getRole(), user.getSecurityVersion(), household.currency(), household.timezone());
    }

    public record LockedHouseholdActor(
            UUID householdId, UUID userId, String role, long securityVersion, String currency, String timezone) {

        public boolean admin() {
            return "ADMIN".equals(role);
        }
    }

    private record HouseholdRow(String currency, String timezone) {}
}
