package com.nayzawoo.expense.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
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
import org.springframework.dao.DataIntegrityViolationException;
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
class AuthSessionIT {

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
    void loginRequiresCsrfThenReadsHouseholdAndLogsOut() {
        insertUser("ada@example.com", "correct-password", "ADMIN", "ACTIVE", false);

        ResponseEntity<String> missingToken = rest.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                jsonEntity("{\"email\":\"ada@example.com\",\"password\":\"correct-password\"}", null, null),
                String.class);
        assertThat(missingToken.getStatusCode().value()).isEqualTo(401);
        assertThat(sessionCookie(missingToken)).contains("HttpOnly");

        Session login = login("ada@example.com", "correct-password");
        assertThat(login.setCookie()).contains("HttpOnly");
        assertThat(login.body().get("email").asString()).isEqualTo("ada@example.com");
        assertThat(login.body().get("mustChangePassword").asBoolean()).isFalse();

        ResponseEntity<String> me = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(me.getBody()).get("role").asString()).isEqualTo("ADMIN");

        ResponseEntity<String> household = rest.exchange(
                "/api/v1/household", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(household.getStatusCode().value()).isEqualTo(200);
        JsonNode householdBody = json.readTree(household.getBody());
        assertThat(householdBody.get("currency").asString()).isEqualTo("MMK");
        assertThat(householdBody.get("timezone").asString()).isEqualTo("Asia/Yangon");

        ResponseEntity<String> logout = rest.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                jsonEntity(null, login.cookie(), login),
                String.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);
        ResponseEntity<String> afterLogout = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(afterLogout.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void rejectsDisabledUserAndWrongPassword() {
        insertUser("ada@example.com", "correct-password", "MEMBER", "DISABLED", false);
        Session csrf = csrf();
        ResponseEntity<String> disabled = rest.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                jsonEntity(
                        "{\"email\":\"ada@example.com\",\"password\":\"correct-password\"}",
                        csrf.cookie(),
                        csrf),
                String.class);
        assertThat(disabled.getStatusCode().value()).isEqualTo(401);

        jdbc.update("UPDATE app_user SET status = 'ACTIVE' WHERE email = 'ada@example.com'");
        Session active = csrf();
        ResponseEntity<String> wrongPassword = rest.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                jsonEntity(
                        "{\"email\":\"Ada@Example.com\",\"password\":\"wrong-password\"}",
                        active.cookie(),
                        active),
                String.class);
        assertThat(wrongPassword.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void temporaryPasswordAllowsOnlySessionRoutes() {
        insertUser("ada@example.com", "correct-password", "MEMBER", "ACTIVE", true);
        Session login = login("ada@example.com", "correct-password");

        ResponseEntity<String> me = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(me.getBody()).get("mustChangePassword").asBoolean()).isTrue();

        ResponseEntity<String> household = rest.exchange(
                "/api/v1/household", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(household.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> logout = rest.exchange(
                "/api/v1/auth/logout",
                HttpMethod.POST,
                jsonEntity(null, login.cookie(), login),
                String.class);
        assertThat(logout.getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void temporaryPasswordChangeClearsFlagAndRevokesSessions() {
        insertUser("ada@example.com", "correct-password", "MEMBER", "ACTIVE", true);
        Session first = login("ada@example.com", "correct-password");
        Session second = login("ada@example.com", "correct-password");

        ResponseEntity<String> changed = rest.exchange(
                "/api/v1/password",
                HttpMethod.PUT,
                jsonEntity(
                        "{\"currentPassword\":\"correct-password\",\"newPassword\":\"replacement-password\"}",
                        first.cookie(),
                        first),
                String.class);
        assertThat(changed.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> afterFirst = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, first.cookie(), null), String.class);
        assertThat(afterFirst.getStatusCode().value()).isEqualTo(401);
        ResponseEntity<String> afterSecond = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, second.cookie(), null), String.class);
        assertThat(afterSecond.getStatusCode().value()).isEqualTo(401);

        Session renewed = login("ada@example.com", "replacement-password");
        assertThat(renewed.body().get("mustChangePassword").asBoolean()).isFalse();
        ResponseEntity<String> household = rest.exchange(
                "/api/v1/household", HttpMethod.GET, jsonEntity(null, renewed.cookie(), null), String.class);
        assertThat(household.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select must_change_password from app_user where email = 'ada@example.com'", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "select password_changed_at is not null from app_user where email = 'ada@example.com'",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select security_version from app_user where email = 'ada@example.com'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void normalPasswordChangeRevokesSessions() {
        insertUser("ada@example.com", "correct-password", "ADMIN", "ACTIVE", false);
        jdbc.update(
                "UPDATE app_user SET password_changed_at = ?, security_version = 3 WHERE email = 'ada@example.com'",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")));
        Session login = login("ada@example.com", "correct-password");

        ResponseEntity<String> changed = rest.exchange(
                "/api/v1/password",
                HttpMethod.PUT,
                jsonEntity(
                        "{\"currentPassword\":\"correct-password\",\"newPassword\":\"another-password\"}",
                        login.cookie(),
                        login),
                String.class);
        assertThat(changed.getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForObject(
                        "select security_version from app_user where email = 'ada@example.com'", Long.class))
                .isEqualTo(4L);
        assertThat(jdbc.queryForObject(
                        """
                        select password_changed_at = ?
                        from app_user where email = 'ada@example.com'
                        """,
                        Boolean.class,
                        java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"))))
                .isTrue();

        ResponseEntity<String> after = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(after.getStatusCode().value()).isEqualTo(401);
        Session renewed = login("ada@example.com", "another-password");
        assertThat(renewed.body().get("mustChangePassword").asBoolean()).isFalse();
    }

    @Test
    void rejectsWrongCurrentPasswordAndWeakNewPassword() {
        insertUser("ada@example.com", "correct-password", "MEMBER", "ACTIVE", true);
        Session login = login("ada@example.com", "correct-password");

        ResponseEntity<String> wrong = rest.exchange(
                "/api/v1/password",
                HttpMethod.PUT,
                jsonEntity(
                        "{\"currentPassword\":\"wrong-password\",\"newPassword\":\"replacement-password\"}",
                        login.cookie(),
                        login),
                String.class);
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<String> weak = rest.exchange(
                "/api/v1/password",
                HttpMethod.PUT,
                jsonEntity(
                        "{\"currentPassword\":\"correct-password\",\"newPassword\":\"short\"}",
                        login.cookie(),
                        login),
                String.class);
        assertThat(weak.getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                        "select must_change_password from app_user where email = 'ada@example.com'", Boolean.class))
                .isTrue();
    }

    @Test
    void rejectsASecondHousehold() {
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO household (id, name, currency, currency_scale, timezone, singleton)
                        VALUES (?, 'Other', 'MMK', 0, 'Asia/Yangon', true)
                        """,
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    void authenticatedSessionEndsThirtyDaysAfterLogin() {
        insertUser("ada@example.com", "correct-password", "ADMIN", "ACTIVE", false);
        Session login = login("ada@example.com", "correct-password");
        when(clock.instant()).thenReturn(Instant.parse("2026-01-31T00:00:00Z"));

        ResponseEntity<String> me = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(401);
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
        return new Session(
                cookie == null ? csrf.cookie() : cookie.split(";", 2)[0],
                cookie == null ? csrf.setCookie() : cookie,
                csrf.headerName(),
                csrf.token(),
                json.readTree(result.getBody()));
    }

    private Session csrf() {
        ResponseEntity<String> result = rest.getForEntity("/api/v1/auth/csrf", String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(result.getBody());
        String cookie = sessionCookie(result);
        assertThat(cookie).isNotNull();
        return new Session(
                cookie.split(";", 2)[0],
                cookie,
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
