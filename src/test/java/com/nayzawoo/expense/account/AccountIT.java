package com.nayzawoo.expense.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
class AccountIT {

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
    private UUID memberId;

    @BeforeEach
    void clear() {
        Instant now = Instant.parse("2026-01-15T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(now, invocation.getArgument(0)));
        jdbc.update(
                """
                TRUNCATE account_posting, transaction_revision, financial_transaction, account,
                         idempotency_record, audit_event, spring_session_attributes, spring_session, app_user, household
                RESTART IDENTITY CASCADE
                """);
        householdId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO household (id, name, currency, currency_scale, timezone, singleton)
                VALUES (?, 'Home', 'MMK', 0, 'Asia/Yangon', true)
                """,
                householdId);
        insertUser(adminId, "admin@example.com", "correct-password", "ADMIN", false);
        insertUser(memberId, "member@example.com", "correct-password", "MEMBER", false);
        jdbc.update(
                "UPDATE app_user SET password_changed_at = ? WHERE id IN (?, ?)",
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                adminId,
                memberId);
    }

    @Test
    void createsAccountWithOpeningAndListsBalances() {
        Session admin = login("admin@example.com", "correct-password");

        ResponseEntity<String> created = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"name":"Cash","type":"CASH","ownerLabel":"Ada","openedOn":"2026-01-10","openingAmount":"1000","sortOrder":1}
                        """,
                        admin.cookie(),
                        admin,
                        "create-cash-1"),
                String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode body = json.readTree(created.getBody());
        assertThat(body.get("openingAmount").asString()).isEqualTo("1000");
        assertThat(body.get("currentBalance").asString()).isEqualTo("1000");
        assertThat(body.get("balanceAsOf").asString()).isEqualTo("2026-01-15");
        assertThat(body.get("balanceVersion").asLong()).isEqualTo(1L);
        assertThat(body.get("negativeBalanceWarning").asBoolean()).isFalse();

        ResponseEntity<String> changedPayload = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"name":"Cash","type":"CASH","ownerLabel":"Other","openedOn":"2026-01-10","openingAmount":"1000","sortOrder":1}
                        """,
                        admin.cookie(),
                        admin,
                        "create-cash-1"),
                String.class);
        assertThat(changedPayload.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> replay = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"name":"Cash","type":"CASH","ownerLabel":"Ada","openedOn":"2026-01-10","openingAmount":"1000","sortOrder":1}
                        """,
                        admin.cookie(),
                        admin,
                        "create-cash-1"),
                String.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(jdbc.queryForObject("select count(*) from account", Long.class)).isEqualTo(1L);

        ResponseEntity<String> zero = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"name":"Wallet","type":"WALLET","ownerLabel":"Ada","openedOn":"2026-01-10","openingAmount":"0","sortOrder":2}
                        """,
                        admin.cookie(),
                        admin,
                        "create-wallet-0"),
                String.class);
        assertThat(zero.getStatusCode().value()).isEqualTo(201);
        assertThat(json.readTree(zero.getBody()).get("currentBalance").asString()).isEqualTo("0");
        assertThat(jdbc.queryForObject(
                        "select count(*) from account_posting p join financial_transaction t on t.id = p.transaction_id where t.opening_account_id = ?",
                        Long.class,
                        UUID.fromString(json.readTree(zero.getBody()).get("id").asString())))
                .isEqualTo(0L);

        ResponseEntity<String> list = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.GET,
                jsonEntity(null, admin.cookie(), null, null),
                String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(list.getBody()).size()).isEqualTo(2);
    }

    @Test
    void rejectsFractionalMoneyForeignHouseholdAndMemberCreate() {
        Session admin = login("admin@example.com", "correct-password");
        Session member = login("member@example.com", "correct-password");

        ResponseEntity<String> fractional = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"name":"Cash","type":"CASH","ownerLabel":"Ada","openedOn":"2026-01-10","openingAmount":"1.0","sortOrder":1}
                        """,
                        admin.cookie(),
                        admin,
                        "frac-1"),
                String.class);
        assertThat(fractional.getStatusCode().value()).isEqualTo(422);

        ResponseEntity<String> foreign = rest.exchange(
                "/api/v1/households/" + UUID.randomUUID() + "/accounts",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"name":"Cash","type":"CASH","ownerLabel":"Ada","openedOn":"2026-01-10","openingAmount":"1","sortOrder":1}
                        """,
                        admin.cookie(),
                        admin,
                        "foreign-1"),
                String.class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<String> memberCreate = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"name":"Cash","type":"CASH","ownerLabel":"Ben","openedOn":"2026-01-10","openingAmount":"1","sortOrder":1}
                        """,
                        member.cookie(),
                        member,
                        "member-1"),
                String.class);
        assertThat(memberCreate.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> memberList = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null, null),
                String.class);
        assertThat(memberList.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void updatesAccountMetadataWithoutChangingOpening() {
        Session admin = login("admin@example.com", "correct-password");
        Session member = login("member@example.com", "correct-password");

        ResponseEntity<String> created = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"name":"Cash","type":"CASH","ownerLabel":"Ada","openedOn":"2026-01-10","openingAmount":"1000","sortOrder":1}
                        """,
                        admin.cookie(),
                        admin,
                        "update-cash-1"),
                String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode createdBody = json.readTree(created.getBody());
        String accountId = createdBody.get("id").asString();
        assertThat(createdBody.get("version").asLong()).isEqualTo(0L);
        assertThat(createdBody.get("openingAmount").asString()).isEqualTo("1000");

        ResponseEntity<String> updated = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts/" + accountId,
                HttpMethod.PUT,
                jsonEntity(
                        """
                        {"name":"Pocket cash","type":"WALLET","ownerLabel":"Ben","sortOrder":5}
                        """,
                        admin.cookie(),
                        admin,
                        null),
                String.class);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        JsonNode updatedBody = json.readTree(updated.getBody());
        assertThat(updatedBody.get("name").asString()).isEqualTo("Pocket cash");
        assertThat(updatedBody.get("type").asString()).isEqualTo("WALLET");
        assertThat(updatedBody.get("ownerLabel").asString()).isEqualTo("Ben");
        assertThat(updatedBody.get("sortOrder").asInt()).isEqualTo(5);
        assertThat(updatedBody.get("openingAmount").asString()).isEqualTo("1000");
        assertThat(updatedBody.get("openedOn").asString()).isEqualTo("2026-01-10");
        assertThat(updatedBody.get("currentBalance").asString()).isEqualTo("1000");
        assertThat(updatedBody.get("version").asLong()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        select r.amount::text from financial_transaction t
                        join transaction_revision r
                          on r.household_id = t.household_id
                         and r.transaction_id = t.id
                         and r.revision_no = t.current_revision_no
                        where t.opening_account_id = ?
                        """,
                        String.class,
                        UUID.fromString(accountId)))
                .isEqualTo("1000");
        assertThat(jdbc.queryForObject(
                        "select opened_on::text from account where id = ?", String.class, UUID.fromString(accountId)))
                .isEqualTo("2026-01-10");

        ResponseEntity<String> memberUpdate = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts/" + accountId,
                HttpMethod.PUT,
                jsonEntity(
                        """
                        {"name":"Nope"}
                        """,
                        member.cookie(),
                        member,
                        null),
                String.class);
        assertThat(memberUpdate.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> empty = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts/" + accountId,
                HttpMethod.PUT,
                jsonEntity("{}", admin.cookie(), admin, null),
                String.class);
        assertThat(empty.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> missing = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts/" + UUID.randomUUID(),
                HttpMethod.PUT,
                jsonEntity(
                        """
                        {"name":"Ghost"}
                        """,
                        admin.cookie(),
                        admin,
                        null),
                String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void refusesWhilePasswordChangeRequired() {
        jdbc.update("UPDATE app_user SET must_change_password = true, password_changed_at = null WHERE id = ?", adminId);
        Session admin = login("admin@example.com", "correct-password");
        ResponseEntity<String> created = rest.exchange(
                "/api/v1/households/" + householdId + "/accounts",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"name":"Cash","type":"CASH","ownerLabel":"Ada","openedOn":"2026-01-10","openingAmount":"1","sortOrder":1}
                        """,
                        admin.cookie(),
                        admin,
                        "temp-1"),
                String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(403);
    }

    private Session login(String email, String password) {
        Session csrf = csrf();
        ResponseEntity<String> result = rest.exchange(
                "/api/v1/auth/login",
                HttpMethod.POST,
                jsonEntity(
                        "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
                        csrf.cookie(),
                        csrf,
                        null),
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

    private HttpEntity<String> jsonEntity(String body, String cookie, Session csrf, String idempotencyKey) {
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
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
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
