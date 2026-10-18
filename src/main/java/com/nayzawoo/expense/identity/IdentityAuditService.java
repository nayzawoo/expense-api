package com.nayzawoo.expense.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class IdentityAuditService {

    static final String EVENT_PASSWORD_ROTATED = "PASSWORD_ROTATED";
    static final String EVENT_TEMPORARY_PASSWORD_ASSIGNED = "TEMPORARY_PASSWORD_ASSIGNED";
    static final String EVENT_OPERATOR_RECOVERY = "OPERATOR_RECOVERY";
    static final String EVENT_LOGIN_EMAIL_CHANGED = "LOGIN_EMAIL_CHANGED";

    static final String RESOURCE_USER = "USER";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    IdentityAuditService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void record(
            UUID householdId,
            UUID actorUserId,
            String eventType,
            String resourceType,
            UUID resourceId,
            String detailsJson) {
        jdbc.update(
                """
                INSERT INTO audit_event (
                    id, household_id, actor_user_id, event_type, resource_type, resource_id,
                    recorded_at, request_id, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, CAST(? AS jsonb))
                """,
                UUID.randomUUID(),
                householdId,
                actorUserId,
                eventType,
                resourceType,
                resourceId,
                Timestamp.from(clock.instant()),
                detailsJson == null || detailsJson.isBlank() ? "{}" : detailsJson);
    }

    static String targetUserDetails(UUID targetUserId) {
        return "{\"targetUserId\":\"" + targetUserId + "\"}";
    }

    static String emailChangeDetails(UUID targetUserId, String previousEmail, String newEmail) {
        return "{\"targetUserId\":\""
                + targetUserId
                + "\",\"previousEmail\":\""
                + escape(previousEmail)
                + "\",\"newEmail\":\""
                + escape(newEmail)
                + "\"}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
