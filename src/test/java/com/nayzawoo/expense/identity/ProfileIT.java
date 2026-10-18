package com.nayzawoo.expense.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class ProfileIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private Clock clock;

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID householdId;
    private UUID userId;

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
        userId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO household (id, name, currency, currency_scale, timezone, singleton)
                VALUES (?, 'Home', 'MMK', 0, 'Asia/Yangon', true)
                """,
                householdId);
        insertUser(userId, "ada@example.com", "correct-password", "MEMBER", "ACTIVE", false);
        jdbc.update(
                "UPDATE app_user SET password_changed_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                userId);
    }

    @Test
    void readsAndUpdatesDisplayName() {
        Session login = login("ada@example.com", "correct-password");

        ResponseEntity<String> profile = rest.exchange(
                "/api/v1/profile", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(profile.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(profile.getBody()).get("email").asString()).isEqualTo("ada@example.com");
        assertThat(json.readTree(profile.getBody()).get("passwordChangeCompleted").asBoolean()).isTrue();

        ResponseEntity<String> updated = rest.exchange(
                "/api/v1/profile",
                HttpMethod.PUT,
                jsonEntity("{\"displayName\":\"Ada Lovelace\"}", login.cookie(), login),
                String.class);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(updated.getBody()).get("displayName").asString()).isEqualTo("Ada Lovelace");
        assertThat(jdbc.queryForObject("select display_name from app_user where id = ?", String.class, userId))
                .isEqualTo("Ada Lovelace");
    }

    @Test
    void emailChangeRequiresPasswordAndRevokesSessions() {
        Session first = login("ada@example.com", "correct-password");
        Session second = login("ada@example.com", "correct-password");

        ResponseEntity<String> missingPassword = rest.exchange(
                "/api/v1/profile",
                HttpMethod.PUT,
                jsonEntity("{\"email\":\"ada.new@example.com\"}", first.cookie(), first),
                String.class);
        assertThat(missingPassword.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<String> wrongPassword = rest.exchange(
                "/api/v1/profile",
                HttpMethod.PUT,
                jsonEntity(
                        "{\"email\":\"ada.new@example.com\",\"currentPassword\":\"wrong-password\"}",
                        first.cookie(),
                        first),
                String.class);
        assertThat(wrongPassword.getStatusCode().value()).isEqualTo(401);

        insertUser(UUID.randomUUID(), "taken@example.com", "other-password", "MEMBER", "ACTIVE", false);
        ResponseEntity<String> duplicate = rest.exchange(
                "/api/v1/profile",
                HttpMethod.PUT,
                jsonEntity(
                        "{\"email\":\"taken@example.com\",\"currentPassword\":\"correct-password\"}",
                        first.cookie(),
                        first),
                String.class);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> changed = rest.exchange(
                "/api/v1/profile",
                HttpMethod.PUT,
                jsonEntity(
                        "{\"email\":\"Ada.New@Example.com\",\"currentPassword\":\"correct-password\"}",
                        first.cookie(),
                        first),
                String.class);
        assertThat(changed.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(changed.getBody()).get("email").asString()).isEqualTo("ada.new@example.com");
        assertThat(jdbc.queryForObject("select security_version from app_user where id = ?", Long.class, userId))
                .isEqualTo(1L);

        ResponseEntity<String> afterFirst = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, first.cookie(), null), String.class);
        assertThat(afterFirst.getStatusCode().value()).isEqualTo(401);
        ResponseEntity<String> afterSecond = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, second.cookie(), null), String.class);
        assertThat(afterSecond.getStatusCode().value()).isEqualTo(401);

        Session renewed = login("ada.new@example.com", "correct-password");
        assertThat(renewed.body().get("email").asString()).isEqualTo("ada.new@example.com");
    }

    @Test
    void refusesProfileWhilePasswordChangeRequired() {
        jdbc.update("UPDATE app_user SET must_change_password = true, password_changed_at = null WHERE id = ?", userId);
        Session login = login("ada@example.com", "correct-password");

        ResponseEntity<String> get = rest.exchange(
                "/api/v1/profile", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(get.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> put = rest.exchange(
                "/api/v1/profile",
                HttpMethod.PUT,
                jsonEntity("{\"displayName\":\"Nope\"}", login.cookie(), login),
                String.class);
        assertThat(put.getStatusCode().value()).isEqualTo(403);
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

    private void insertUser(
            UUID id, String email, String password, String role, String status, boolean mustChangePassword) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, household_id, email, display_name, password_hash, role, status,
                    must_change_password, security_version)
                VALUES (?, ?, ?, 'Ada', ?, ?, ?, ?, 0)
                """,
                id,
                householdId,
                email,
                passwordEncoder.encode(password),
                role,
                status,
                mustChangePassword);
    }

    private record Session(String cookie, String setCookie, String headerName, String token, JsonNode body) {}
}
