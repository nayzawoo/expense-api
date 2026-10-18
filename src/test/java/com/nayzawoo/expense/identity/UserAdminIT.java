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
class UserAdminIT {

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
    private UUID adminId;

    @BeforeEach
    void clearIdentity() {
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
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
    void adminCreatesListsAndReadsUsers() {
        Session admin = login("ada@example.com", "correct-password");

        ResponseEntity<String> created = rest.exchange(
                "/api/v1/households/" + householdId + "/users",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"displayName":"Ben","email":"Ben@Example.com","temporaryPassword":"temporary-password","role":"MEMBER"}
                        """,
                        admin.cookie(),
                        admin),
                String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        JsonNode createdBody = json.readTree(created.getBody());
        assertThat(createdBody.get("email").asString()).isEqualTo("ben@example.com");
        assertThat(createdBody.get("mustChangePassword").asBoolean()).isTrue();
        assertThat(createdBody.get("passwordChangeCompleted").asBoolean()).isFalse();
        assertThat(created.getBody()).doesNotContain("temporary");

        ResponseEntity<String> duplicate = rest.exchange(
                "/api/v1/households/" + householdId + "/users",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"displayName":"Other","email":"ben@example.com","temporaryPassword":"temporary-password","role":"MEMBER"}
                        """,
                        admin.cookie(),
                        admin),
                String.class);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> list = rest.exchange(
                "/api/v1/households/" + householdId + "/users",
                HttpMethod.GET,
                jsonEntity(null, admin.cookie(), null),
                String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(list.getBody()).size()).isEqualTo(2);

        String userId = createdBody.get("id").asString();
        ResponseEntity<String> detail = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + userId,
                HttpMethod.GET,
                jsonEntity(null, admin.cookie(), null),
                String.class);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(detail.getBody()).get("displayName").asString()).isEqualTo("Ben");

        ResponseEntity<String> foreign = rest.exchange(
                "/api/v1/households/" + UUID.randomUUID() + "/users",
                HttpMethod.GET,
                jsonEntity(null, admin.cookie(), null),
                String.class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void protectsLastActiveAdminAndRejectsSelfRoleStatusEmail() {
        Session admin = login("ada@example.com", "correct-password");

        ResponseEntity<String> demoteSelf = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + adminId,
                HttpMethod.PATCH,
                jsonEntity("{\"role\":\"MEMBER\"}", admin.cookie(), admin),
                String.class);
        assertThat(demoteSelf.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> disableSelf = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + adminId,
                HttpMethod.PATCH,
                jsonEntity("{\"status\":\"DISABLED\"}", admin.cookie(), admin),
                String.class);
        assertThat(disableSelf.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> emailSelf = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + adminId,
                HttpMethod.PATCH,
                jsonEntity("{\"email\":\"other@example.com\"}", admin.cookie(), admin),
                String.class);
        assertThat(emailSelf.getStatusCode().value()).isEqualTo(403);

        UUID secondAdmin = UUID.randomUUID();
        insertUser(secondAdmin, "bea@example.com", "correct-password", "ADMIN", "ACTIVE", false);
        Session otherAdmin = login("bea@example.com", "correct-password");

        ResponseEntity<String> demoteOther = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + secondAdmin,
                HttpMethod.PATCH,
                jsonEntity("{\"role\":\"MEMBER\"}", admin.cookie(), admin),
                String.class);
        assertThat(demoteOther.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select role from app_user where id = ?", String.class, secondAdmin))
                .isEqualTo("MEMBER");

        ResponseEntity<String> demoteLast = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + adminId,
                HttpMethod.PATCH,
                jsonEntity("{\"role\":\"MEMBER\"}", otherAdmin.cookie(), otherAdmin),
                String.class);
        assertThat(demoteLast.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> disableLast = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + adminId,
                HttpMethod.PATCH,
                jsonEntity("{\"status\":\"DISABLED\"}", otherAdmin.cookie(), otherAdmin),
                String.class);
        assertThat(disableLast.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void temporaryPasswordAndEmailChangeRevokeSessions() {
        UUID memberId = UUID.randomUUID();
        insertUser(memberId, "ben@example.com", "member-password", "MEMBER", "ACTIVE", false);
        Session admin = login("ada@example.com", "correct-password");
        Session member = login("ben@example.com", "member-password");

        ResponseEntity<String> reset = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + memberId + "/temporary-password",
                HttpMethod.POST,
                jsonEntity("{\"temporaryPassword\":\"fresh-temporary\"}", admin.cookie(), admin),
                String.class);
        assertThat(reset.getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForObject(
                        "select must_change_password from app_user where id = ?", Boolean.class, memberId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select security_version from app_user where id = ?", Long.class, memberId))
                .isEqualTo(1L);

        ResponseEntity<String> afterReset = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, member.cookie(), null), String.class);
        assertThat(afterReset.getStatusCode().value()).isEqualTo(401);

        Session renewed = login("ben@example.com", "fresh-temporary");
        assertThat(renewed.body().get("mustChangePassword").asBoolean()).isTrue();

        ResponseEntity<String> emailChanged = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + memberId,
                HttpMethod.PATCH,
                jsonEntity("{\"email\":\"ben.new@example.com\"}", admin.cookie(), admin),
                String.class);
        assertThat(emailChanged.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(emailChanged.getBody()).get("email").asString()).isEqualTo("ben.new@example.com");
        assertThat(jdbc.queryForObject(
                        "select security_version from app_user where id = ?", Long.class, memberId))
                .isEqualTo(2L);

        ResponseEntity<String> afterEmail = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, renewed.cookie(), null), String.class);
        assertThat(afterEmail.getStatusCode().value()).isEqualTo(401);
        Session renamed = login("ben.new@example.com", "fresh-temporary");
        assertThat(renamed.body().get("email").asString()).isEqualTo("ben.new@example.com");
    }

    @Test
    void memberCannotAdministerUsers() {
        insertUser(UUID.randomUUID(), "ben@example.com", "member-password", "MEMBER", "ACTIVE", false);
        Session member = login("ben@example.com", "member-password");
        ResponseEntity<String> list = rest.exchange(
                "/api/v1/households/" + householdId + "/users",
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null),
                String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(403);
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
                email.startsWith("ada") ? "Ada" : "Ben",
                passwordEncoder.encode(password),
                role,
                status,
                mustChangePassword);
    }

    private record Session(String cookie, String setCookie, String headerName, String token, JsonNode body) {}
}
