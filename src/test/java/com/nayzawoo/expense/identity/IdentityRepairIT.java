package com.nayzawoo.expense.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
class IdentityRepairIT {

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
    private HouseholdUserService householdUsers;

    @Autowired
    private AppUserRepository users;

    @MockitoBean
    private Clock clock;

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID householdId;
    private UUID adminId;
    private UUID otherAdminId;

    @BeforeEach
    void clear() {
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM household");
        householdId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        otherAdminId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO household (id, name, currency, currency_scale, timezone, singleton)
                VALUES (?, 'Home', 'MMK', 0, 'Asia/Yangon', true)
                """,
                householdId);
        insertUser(adminId, "ada@example.com", "correct-password", "ADMIN", false);
        insertUser(otherAdminId, "bea@example.com", "correct-password", "ADMIN", false);
    }

    @Test
    void disableRevokesSessionsAndSurvivesReenable() {
        Session admin = login("ada@example.com", "correct-password");
        Session memberSession = login("bea@example.com", "correct-password");

        ResponseEntity<String> disabled = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + otherAdminId,
                HttpMethod.PATCH,
                jsonEntity("{\"status\":\"DISABLED\"}", admin.cookie(), admin),
                String.class);
        assertThat(disabled.getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select security_version from app_user where id = ?", Long.class, otherAdminId))
                .isEqualTo(1L);

        ResponseEntity<String> afterDisable = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, memberSession.cookie(), null), String.class);
        assertThat(afterDisable.getStatusCode().value()).isEqualTo(401);

        ResponseEntity<String> reenabled = rest.exchange(
                "/api/v1/households/" + householdId + "/users/" + otherAdminId,
                HttpMethod.PATCH,
                jsonEntity("{\"status\":\"ACTIVE\"}", admin.cookie(), admin),
                String.class);
        assertThat(reenabled.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> afterReenable = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, memberSession.cookie(), null), String.class);
        assertThat(afterReenable.getStatusCode().value()).isEqualTo(401);

        Session renewed = login("bea@example.com", "correct-password");
        ResponseEntity<String> me = rest.exchange(
                "/api/v1/auth/me", HttpMethod.GET, jsonEntity(null, renewed.cookie(), null), String.class);
        assertThat(me.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void concurrentDemotionLeavesOneActiveAdmin() throws Exception {
        AppUser ada = users.findById(adminId).orElseThrow();
        AppUser bea = users.findById(otherAdminId).orElseThrow();
        ExpenseUser adaActor = new ExpenseUser(ada);
        ExpenseUser beaActor = new ExpenseUser(bea);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                ready.countDown();
                await(go);
                try {
                    householdUsers.updateUser(adaActor, householdId, otherAdminId, null, "MEMBER", null, null);
                    successes.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    int code = ex.getStatusCode().value();
                    if (code == 403 || code == 409) {
                        rejected.incrementAndGet();
                    } else {
                        throw ex;
                    }
                }
            });
            Future<?> second = pool.submit(() -> {
                ready.countDown();
                await(go);
                try {
                    householdUsers.updateUser(beaActor, householdId, adminId, null, "MEMBER", null, null);
                    successes.incrementAndGet();
                } catch (ResponseStatusException ex) {
                    int code = ex.getStatusCode().value();
                    if (code == 403 || code == 409) {
                        rejected.incrementAndGet();
                    } else {
                        throw ex;
                    }
                }
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(successes.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(1);
        assertThat(users.countByHouseholdIdAndRoleAndStatus(householdId, "ADMIN", "ACTIVE")).isEqualTo(1L);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
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
