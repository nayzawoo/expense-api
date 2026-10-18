package com.nayzawoo.expense.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class IdentityAuditIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RecoveryService recoveryService;

    @MockitoBean
    private Clock clock;

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID householdId;
    private UUID adminId;
    private UUID memberId;

    @BeforeEach
    void clearIdentity() {
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM household");
        householdId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO household (id, name, currency, currency_scale, timezone, singleton)
                VALUES (?, 'Home', 'MMK', 0, 'Asia/Yangon', true)
                """,
                householdId);
        insertUser(adminId, "ada@example.com", "correct-password", "ADMIN", false);
        insertUser(memberId, "ben@example.com", "member-password", "MEMBER", false);
        jdbc.update(
                "UPDATE app_user SET password_changed_at = ? WHERE id IN (?, ?)",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                adminId,
                memberId);
    }

    @Test
    void recordsRequiredIdentityAuditEventsWithoutCredentials() {
        Session admin = login("ada@example.com", "correct-password");

        ResponseEntity<String> reset = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + memberId + "/temporary-password",
                HttpMethod.POST,
                jsonEntity("{\"temporaryPassword\":\"fresh-temporary\"}", admin.cookie(), admin),
                String.class);
        assertThat(reset.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> email = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + memberId,
                HttpMethod.PATCH,
                jsonEntity("{\"email\":\"ben.new@example.com\"}", admin.cookie(), admin),
                String.class);
        assertThat(email.getStatusCode().value()).isEqualTo(200);

        Session member = login("ben.new@example.com", "fresh-temporary");
        ResponseEntity<String> rotated = rest.exchange(
                "/api/v1/password",
                HttpMethod.PUT,
                jsonEntity(
                        "{\"currentPassword\":\"fresh-temporary\",\"newPassword\":\"replacement-password\"}",
                        member.cookie(),
                        member),
                String.class);
        assertThat(rotated.getStatusCode().value()).isEqualTo(204);

        recoveryService.recover("ada@example.com", "recovered-password");

        assertThat(count("TEMPORARY_PASSWORD_ASSIGNED")).isEqualTo(1L);
        assertThat(count("LOGIN_EMAIL_CHANGED")).isEqualTo(1L);
        assertThat(count("PASSWORD_ROTATED")).isEqualTo(1L);
        assertThat(count("OPERATOR_RECOVERY")).isEqualTo(1L);

        Map<String, Object> recovery = jdbc.queryForMap(
                """
                SELECT actor_user_id, resource_id, details::text AS details
                FROM audit_event
                WHERE event_type = 'OPERATOR_RECOVERY'
                """);
        assertThat(recovery.get("actor_user_id")).isNull();
        assertThat(recovery.get("resource_id")).isEqualTo(adminId);
        assertThat(recovery.get("details").toString()).contains(adminId.toString());
        assertThat(recovery.get("details").toString()).doesNotContain("recovered-password");
        assertThat(recovery.get("details").toString()).doesNotContain("fresh-temporary");

        Map<String, Object> emailChange = jdbc.queryForMap(
                """
                SELECT actor_user_id, details::text AS details
                FROM audit_event
                WHERE event_type = 'LOGIN_EMAIL_CHANGED'
                """);
        assertThat(emailChange.get("actor_user_id")).isEqualTo(adminId);
        assertThat(emailChange.get("details").toString()).contains("ben@example.com");
        assertThat(emailChange.get("details").toString()).contains("ben.new@example.com");
    }

    private long count(String eventType) {
        return jdbc.queryForObject(
                "select count(*) from audit_event where event_type = ?", Long.class, eventType);
    }

    private Session login(String email, String password) {
        Session csrf = csrf();
        ResponseEntity<String> result = rest.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                jsonEntity(
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
                        csrf.cookie(),
                        csrf),
                String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        String cookie = sessionCookie(result);
        String sessionCookieValue = cookie == null ? csrf.cookie() : cookie.split(";", 2)[0];
        Session refreshed = csrfFor(sessionCookieValue);
        return new Session(
                sessionCookieValue,
                cookie == null ? csrf.setCookie() : cookie,
                refreshed.headerName(),
                refreshed.token(),
                json.readTree(result.getBody()));
    }

    private Session csrf() {
        return csrfFor(null);
    }

    private Session csrfFor(String cookie) {
        HttpHeaders headers = new HttpHeaders();
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        ResponseEntity<String> result =
                rest.exchange("/api/v1/auth/csrf", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(result.getBody());
        String responseCookie = sessionCookie(result);
        String sessionCookieValue =
                responseCookie == null ? cookie : responseCookie.split(";", 2)[0];
        assertThat(sessionCookieValue).isNotNull();
        return new Session(
                sessionCookieValue,
                responseCookie == null ? cookie : responseCookie,
                body.get("headerName").asString(),
                body.get("token").asString(),
                body);
    }

    private HttpEntity<String> jsonEntity(String body, String cookie, Session csrf) {
        HttpHeaders headers = new HttpHeaders();
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, cookie);
        }
        if (csrf != null) {
            headers.add(csrf.headerName(), csrf.token());
        }
        return new HttpEntity<>(body, headers);
    }

    private static String sessionCookie(ResponseEntity<?> response) {
        return response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith("expense-session="))
                .findFirst()
                .orElse(null);
    }

    private void insertUser(UUID id, String email, String password, String role, boolean mustChangePassword) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, household_id, email, display_name, password_hash, role, status,
                    must_change_password, security_version)
                VALUES (?, ?, ?, 'User', ?, ?, 'ACTIVE', ?, 0)
                """,
                id,
                householdId,
                email,
                passwordEncoder.encode(password),
                role,
                mustChangePassword);
    }

    private record Session(String cookie, String setCookie, String headerName, String token, JsonNode body) {}
}
