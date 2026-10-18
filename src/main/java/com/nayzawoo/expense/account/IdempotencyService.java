package com.nayzawoo.expense.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    IdempotencyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<Replay> begin(
            UUID householdId, UUID actorUserId, String operation, String key, String requestHash) {
        requireKey(key);
        Instant now = Instant.now(clock);
        int inserted = jdbc.update(
                """
                INSERT INTO idempotency_record (
                    id, household_id, actor_user_id, operation, key, request_hash,
                    http_status, resource_id, resource_version, response_body, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, NULL, NULL, '', ?, ?)
                ON CONFLICT (household_id, actor_user_id, operation, key) DO NOTHING
                """,
                UUID.randomUUID(),
                householdId,
                actorUserId,
                operation,
                key,
                requestHash,
                Timestamp.from(now),
                Timestamp.from(now.plus(7, ChronoUnit.DAYS)));
        if (inserted == 1) {
            return Optional.empty();
        }
        return Optional.of(readRequired(householdId, actorUserId, operation, key, requestHash));
    }

    void complete(
            UUID householdId,
            UUID actorUserId,
            String operation,
            String key,
            int httpStatus,
            UUID resourceId,
            long resourceVersion,
            String responseBody) {
        int updated = jdbc.update(
                """
                UPDATE idempotency_record
                SET http_status = ?, resource_id = ?, resource_version = ?, response_body = ?
                WHERE household_id = ?
                  AND actor_user_id = ?
                  AND operation = ?
                  AND key = ?
                  AND http_status = 0
                """,
                httpStatus,
                resourceId,
                resourceVersion,
                responseBody,
                householdId,
                actorUserId,
                operation,
                key);
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
        }
    }

    static String canonical(String... fields) {
        StringBuilder builder = new StringBuilder();
        for (String field : fields) {
            String value = field == null ? "" : field;
            builder.append(value.length()).append(':').append(value).append('|');
        }
        return builder.toString();
    }

    static String hashRequest(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Replay readRequired(
            UUID householdId, UUID actorUserId, String operation, String key, String requestHash) {
        return jdbc.query(
                """
                SELECT request_hash, http_status, resource_id, resource_version, response_body
                FROM idempotency_record
                WHERE household_id = ?
                  AND actor_user_id = ?
                  AND operation = ?
                  AND key = ?
                  AND expires_at > ?
                FOR UPDATE
                """,
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
                    }
                    if (!requestHash.equals(rs.getString("request_hash"))) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
                    }
                    int status = rs.getInt("http_status");
                    if (status == 0) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
                    }
                    UUID resourceId = rs.getObject("resource_id", UUID.class);
                    Long version = rs.getObject("resource_version", Long.class);
                    return new Replay(status, resourceId, version == null ? 0L : version, rs.getString("response_body"));
                },
                householdId,
                actorUserId,
                operation,
                key,
                Timestamp.from(Instant.now(clock)));
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    record Replay(int httpStatus, UUID resourceId, long resourceVersion, String responseBody) {}
}
