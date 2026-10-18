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
class SessionActivityIT {

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
        jdbc.update(
                """
                INSERT INTO household (id, name, currency, currency_scale, timezone, singleton)
                VALUES (?, 'Home', 'MMK', 0, 'Asia/Yangon', true)
                """,
                householdId);
    }

    @Test
    void passiveReadsDoNotRenewIdleAndActivityDoes() {
        insertUser("ada@example.com", "correct-password", "ADMIN", "ACTIVE", false);
        Session login = login("ada@example.com", "correct-password");

        when(clock.instant()).thenReturn(Instant.parse("2026-01-07T00:00:00Z"));
        ResponseEntity<String> meBeforeExpiry = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(meBeforeExpiry.getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> csrfBeforeExpiry = rest.exchange(
                "/api/v1/auth/csrf",
                HttpMethod.GET,
                jsonEntity(null, login.cookie(), null),
                String.class);
        assertThat(csrfBeforeExpiry.getStatusCode().value()).isEqualTo(200);

        when(clock.instant()).thenReturn(Instant.parse("2026-01-08T00:00:00Z"));
        ResponseEntity<String> idleExpired = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(idleExpired.getStatusCode().value()).isEqualTo(401);

        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        Session renewedLogin = login("ada@example.com", "correct-password");
        when(clock.instant()).thenReturn(Instant.parse("2026-01-07T00:00:00Z"));
        ResponseEntity<String> touched = rest.exchange(
                "/api/v1/auth/activity",
                HttpMethod.POST,
                jsonEntity(null, renewedLogin.cookie(), renewedLogin),
                String.class);
        assertThat(touched.getStatusCode().value()).isEqualTo(204);

        when(clock.instant()).thenReturn(Instant.parse("2026-01-13T00:00:00Z"));
        ResponseEntity<String> afterTouch = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, renewedLogin.cookie(), null), String.class);
        assertThat(afterTouch.getStatusCode().value()).isEqualTo(200);

        when(clock.instant()).thenReturn(Instant.parse("2026-01-14T00:00:00Z"));
        ResponseEntity<String> afterIdle = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, renewedLogin.cookie(), null), String.class);
        assertThat(afterIdle.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void absoluteExpiryWinsEvenWithActivity() {
        insertUser("ada@example.com", "correct-password", "ADMIN", "ACTIVE", false);
        Session login = login("ada@example.com", "correct-password");

        when(clock.instant()).thenReturn(Instant.parse("2026-01-07T00:00:00Z"));
        assertThat(rest.exchange(
                        "/api/v1/auth/activity",
                        HttpMethod.POST,
                        jsonEntity(null, login.cookie(), login),
                        String.class)
                .getStatusCode()
                .value())
                .isEqualTo(204);
        when(clock.instant()).thenReturn(Instant.parse("2026-01-13T00:00:00Z"));
        assertThat(rest.exchange(
                        "/api/v1/auth/activity",
                        HttpMethod.POST,
                        jsonEntity(null, login.cookie(), login),
                        String.class)
                .getStatusCode()
                .value())
                .isEqualTo(204);
        when(clock.instant()).thenReturn(Instant.parse("2026-01-19T00:00:00Z"));
        assertThat(rest.exchange(
                        "/api/v1/auth/activity",
                        HttpMethod.POST,
                        jsonEntity(null, login.cookie(), login),
                        String.class)
                .getStatusCode()
                .value())
                .isEqualTo(204);
        when(clock.instant()).thenReturn(Instant.parse("2026-01-25T00:00:00Z"));
        assertThat(rest.exchange(
                        "/api/v1/auth/activity",
                        HttpMethod.POST,
                        jsonEntity(null, login.cookie(), login),
                        String.class)
                .getStatusCode()
                .value())
                .isEqualTo(204);

        when(clock.instant()).thenReturn(Instant.parse("2026-01-31T00:00:00Z"));
        ResponseEntity<String> absolute = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(absolute.getStatusCode().value()).isEqualTo(401);
        ResponseEntity<String> activityRejected = rest.exchange(
                "/api/v1/auth/activity",
                HttpMethod.POST,
                jsonEntity(null, login.cookie(), login),
                String.class);
        assertThat(activityRejected.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void temporaryPasswordCannotTouchActivity() {
        insertUser("ada@example.com", "correct-password", "MEMBER", "ACTIVE", true);
        Session login = login("ada@example.com", "correct-password");
        ResponseEntity<String> activity = rest.exchange(
                "/api/v1/auth/activity",
                HttpMethod.POST,
                jsonEntity(null, login.cookie(), login),
                String.class);
        assertThat(activity.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void activityRequiresCsrf() {
        insertUser("ada@example.com", "correct-password", "ADMIN", "ACTIVE", false);
        Session login = login("ada@example.com", "correct-password");
        ResponseEntity<String> missing = rest.exchange(
                "/api/v1/auth/activity",
                HttpMethod.POST,
                jsonEntity(null, login.cookie(), null),
                String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(403);
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

    private void insertUser(String email, String password, String role, String status, boolean mustChangePassword) {
        jdbc.update(
                """
                INSERT INTO app_user (
                    id, household_id, email, display_name, password_hash, role, status,
                    must_change_password, security_version)
                VALUES (?, ?, ?, 'Ada', ?, ?, ?, ?, 0)
                """,
                UUID.randomUUID(),
                householdId,
                email,
                passwordEncoder.encode(password),
                role,
                status,
                mustChangePassword);
    }

    private record Session(String cookie, String setCookie, String headerName, String token, JsonNode body) {}
}
