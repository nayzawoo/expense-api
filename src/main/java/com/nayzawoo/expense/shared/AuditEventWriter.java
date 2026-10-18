package com.nayzawoo.expense.shared;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditEventWriter {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditEventWriter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void record(
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

    public static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
