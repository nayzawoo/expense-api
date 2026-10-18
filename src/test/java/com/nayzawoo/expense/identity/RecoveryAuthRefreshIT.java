package com.nayzawoo.expense.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class RecoveryAuthRefreshIT {

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

    @Autowired
    private HouseholdUserService householdUsers;

    @Autowired
    private AppUserRepository users;

    @MockitoBean
    private Clock clock;

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID householdId;
    private UUID adminId;

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
        jdbc.update(
                """
                INSERT INTO household (id, name, currency, currency_scale, timezone, singleton)
                VALUES (?, 'Home', 'MMK', 0, 'Asia/Yangon', true)
                """,
                householdId);
        insertUser(adminId, "ada@example.com", "correct-password", "ADMIN", "ACTIVE", false);
    }

    @Test
    void recoveryResetsAdminAndRevokesSessions() {
        Session admin = login("ada@example.com", "correct-password");
        RecoveryService.RecoveryResult result = recoveryService.recover("Ada@Example.com", "recovered-password");

        assertThat(result.adminEmail()).isEqualTo("ada@example.com");
        assertThat(jdbc.queryForObject(
                        "select must_change_password from app_user where id = ?", Boolean.class, adminId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select security_version from app_user where id = ?", Long.class, adminId))
                .isEqualTo(1L);

        ResponseEntity<String> after = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, admin.cookie(), null), String.class);
        assertThat(after.getStatusCode().value()).isEqualTo(401);

        Session recovered = login("ada@example.com", "recovered-password");
        assertThat(recovered.body().get("mustChangePassword").asBoolean()).isTrue();
        ResponseEntity<String> household = rest.exchange(
                "/api/v1/household", HttpMethod.GET, jsonEntity(null, recovered.cookie(), null), String.class);
        assertThat(household.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void recoveryRefusesDisabledAndNonAdminTargets() {
        UUID memberId = UUID.randomUUID();
        insertUser(memberId, "ben@example.com", "member-password", "MEMBER", "ACTIVE", false);
        assertThatThrownBy(() -> recoveryService.recover("ben@example.com", "recovered-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active admin");

        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", adminId);
        assertThatThrownBy(() -> recoveryService.recover("ada@example.com", "recovered-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active admin");
    }

    @Test
    void demotionDisableAndSecurityVersionTakeEffectOnNextRequest() {
        UUID secondAdmin = UUID.randomUUID();
        insertUser(secondAdmin, "bea@example.com", "correct-password", "ADMIN", "ACTIVE", false);
        Session ada = login("ada@example.com", "correct-password");
        Session bea = login("bea@example.com", "correct-password");

        ResponseEntity<String> demote = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + secondAdmin,
                HttpMethod.PATCH,
                jsonEntity("{\"role\":\"MEMBER\"}", ada.cookie(), ada),
                String.class);
        assertThat(demote.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> beaAfterDemote = rest.exchange(
                "/api/v1/households/" + householdId + "/users",
                HttpMethod.GET,
                jsonEntity(null, bea.cookie(), null),
                String.class);
        assertThat(beaAfterDemote.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> me = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, bea.cookie(), null), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(me.getBody()).get("role").asString()).isEqualTo("MEMBER");

        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", secondAdmin);
        ResponseEntity<String> afterDisable = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, bea.cookie(), null), String.class);
        assertThat(afterDisable.getStatusCode().value()).isEqualTo(401);

        Session adaAgain = login("ada@example.com", "correct-password");
        UUID memberId = UUID.randomUUID();
        insertUser(memberId, "ben@example.com", "member-password", "MEMBER", "ACTIVE", false);
        Session member = login("ben@example.com", "member-password");
        rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + memberId + "/temporary-password",
                HttpMethod.POST,
                jsonEntity("{\"temporaryPassword\":\"fresh-temporary\"}", adaAgain.cookie(), adaAgain),
                String.class);
        ResponseEntity<String> afterReset = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, member.cookie(), null), String.class);
        assertThat(afterReset.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void serviceStillRejectsDemotingTheLastActiveAdmin() {
        UUID secondAdmin = UUID.randomUUID();
        insertUser(secondAdmin, "bea@example.com", "correct-password", "ADMIN", "ACTIVE", false);
        AppUser beaBeforeDemote = users.findById(secondAdmin).orElseThrow();
        ExpenseUser staleBea = new ExpenseUser(beaBeforeDemote);
        ExpenseUser ada = new ExpenseUser(users.findById(adminId).orElseThrow());

        householdUsers.updateUser(ada, householdId, secondAdmin, null, "MEMBER", null, null);
        assertThatThrownBy(() -> householdUsers.updateUser(staleBea, householdId, adminId, null, "MEMBER", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode().value())
                .isEqualTo(403);
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
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                id,
                householdId,
                email,
                email.startsWith("ada") ? "Ada" : "User",
                passwordEncoder.encode(password),
                role,
                status,
                mustChangePassword);
    }

    private record Session(String cookie, String setCookie, String headerName, String token, JsonNode body) {}
}
