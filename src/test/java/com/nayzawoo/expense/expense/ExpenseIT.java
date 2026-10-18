package com.nayzawoo.expense.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class ExpenseIT {

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
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private Clock clock;

    private final JsonMapper json = JsonMapper.builder().build();
    private UUID householdId;
    private UUID adminId;
    private UUID memberId;
    private UUID accountId;
    private UUID categoryId;
    private UUID beneficiaryId;

    @BeforeEach
    void clear() {
        Instant now = Instant.parse("2026-01-15T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneId.of("UTC"));
        when(clock.withZone(any())).thenAnswer(invocation -> Clock.fixed(now, invocation.getArgument(0)));
        jdbc.update(
                """
                TRUNCATE account_posting, transaction_revision, financial_transaction, account,
                         category, beneficiary, idempotency_record, audit_event,
                         spring_session_attributes, spring_session, app_user, household
                RESTART IDENTITY CASCADE
                """);
        householdId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        beneficiaryId = UUID.randomUUID();
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
        insertAccount(accountId, "Cash", "2026-01-10", "1000");
        jdbc.update(
                """
                INSERT INTO category (
                    id, household_id, name, color, icon_key, sort_order, status, version, created_at, updated_at)
                VALUES (?, ?, 'Food', '#AABBCC', 'food', 1, 'ACTIVE', 0, ?, ?)
                """,
                categoryId,
                householdId,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
        jdbc.update(
                """
                INSERT INTO beneficiary (
                    id, household_id, name, kind, sort_order, status, version, created_at, updated_at)
                VALUES (?, ?, 'Household', 'HOUSEHOLD', 1, 'ACTIVE', 0, ?, ?)
                """,
                beneficiaryId,
                householdId,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
    }

    @Test
    void memberCreatesExpenseDebitsBalanceAndReplaysIdempotently() {
        Session member = login("member@example.com", "correct-password");
        String body = """
                {"effectiveDate":"2026-01-12","amount":"250","accountId":"%s","categoryId":"%s","beneficiaryId":"%s","note":"Groceries"}
                """
                .formatted(accountId, categoryId, beneficiaryId);

        ResponseEntity<String> created = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses",
                HttpMethod.POST,
                jsonEntity(body, member.cookie(), member, "expense-1"),
                String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode createdBody = json.readTree(created.getBody());
        assertThat(createdBody.get("amount").asString()).isEqualTo("250");
        assertThat(createdBody.get("state").asString()).isEqualTo("POSTED");
        assertThat(createdBody.get("account").get("name").asString()).isEqualTo("Cash");
        assertThat(createdBody.get("capabilities").get("canReplace").asBoolean()).isTrue();
        String expenseId = createdBody.get("id").asString();

        BigDecimal balance = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(signed_amount), 0)
                FROM account_posting
                WHERE household_id = ? AND account_id = ?
                """,
                BigDecimal.class,
                householdId,
                accountId);
        assertThat(balance).isEqualByComparingTo("750");
        assertThat(jdbc.queryForObject(
                        "SELECT balance_version FROM account WHERE id = ?", Long.class, accountId))
                .isEqualTo(2L);

        ResponseEntity<String> changed = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses",
                HttpMethod.POST,
                jsonEntity(
                        """
                        {"effectiveDate":"2026-01-12","amount":"251","accountId":"%s","categoryId":"%s","beneficiaryId":"%s","note":"Groceries"}
                        """
                                .formatted(accountId, categoryId, beneficiaryId),
                        member.cookie(),
                        member,
                        "expense-1"),
                String.class);
        assertThat(changed.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> replay = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses",
                HttpMethod.POST,
                jsonEntity(body, member.cookie(), member, "expense-1"),
                String.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(201);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM financial_transaction WHERE kind = 'EXPENSE'", Long.class))
                .isEqualTo(1L);

        ResponseEntity<String> detail = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses/" + expenseId,
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null, null),
                String.class);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(detail.getBody()).get("note").asString()).isEqualTo("Groceries");

        ResponseEntity<String> list = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses",
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null, null),
                String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(list.getBody()).size()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidMoneyDatesArchivedAndForeignReferences() {
        Session member = login("member@example.com", "correct-password");
        UUID archivedCategory = UUID.randomUUID();
        Instant now = Instant.parse("2026-01-15T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO category (
                    id, household_id, name, color, icon_key, sort_order, status, version, created_at, updated_at)
                VALUES (?, ?, 'Old', '#112233', 'other', 2, 'ARCHIVED', 0, ?, ?)
                """,
                archivedCategory,
                householdId,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));

        assertThat(postExpense(
                        member,
                        """
                        {"effectiveDate":"2026-01-12","amount":"1.0","accountId":"%s","categoryId":"%s","beneficiaryId":"%s"}
                        """
                                .formatted(accountId, categoryId, beneficiaryId),
                        "frac-1")
                .getStatusCode()
                .value())
                .isEqualTo(422);
        assertThat(postExpense(
                        member,
                        """
                        {"effectiveDate":"2026-01-12","amount":"0","accountId":"%s","categoryId":"%s","beneficiaryId":"%s"}
                        """
                                .formatted(accountId, categoryId, beneficiaryId),
                        "zero-1")
                .getStatusCode()
                .value())
                .isEqualTo(422);
        assertThat(postExpense(
                        member,
                        """
                        {"effectiveDate":"2026-01-09","amount":"10","accountId":"%s","categoryId":"%s","beneficiaryId":"%s"}
                        """
                                .formatted(accountId, categoryId, beneficiaryId),
                        "before-1")
                .getStatusCode()
                .value())
                .isEqualTo(422);
        assertThat(postExpense(
                        member,
                        """
                        {"effectiveDate":"2026-01-16","amount":"10","accountId":"%s","categoryId":"%s","beneficiaryId":"%s"}
                        """
                                .formatted(accountId, categoryId, beneficiaryId),
                        "future-1")
                .getStatusCode()
                .value())
                .isEqualTo(422);
        assertThat(postExpense(
                        member,
                        """
                        {"effectiveDate":"2026-01-12","amount":"10","accountId":"%s","categoryId":"%s","beneficiaryId":"%s"}
                        """
                                .formatted(accountId, archivedCategory, beneficiaryId),
                        "archived-1")
                .getStatusCode()
                .value())
                .isEqualTo(422);
        assertThat(postExpense(
                        member,
                        """
                        {"effectiveDate":"2026-01-12","amount":"10","accountId":"%s","categoryId":"%s","beneficiaryId":"%s"}
                        """
                                .formatted(accountId, UUID.randomUUID(), beneficiaryId),
                        "foreign-cat-1")
                .getStatusCode()
                .value())
                .isEqualTo(404);
        assertThat(rest.exchange(
                        "/api/v1/households/" + UUID.randomUUID() + "/expenses",
                        HttpMethod.POST,
                        jsonEntity(
                                """
                                {"effectiveDate":"2026-01-12","amount":"10","accountId":"%s","categoryId":"%s","beneficiaryId":"%s"}
                                """
                                        .formatted(accountId, categoryId, beneficiaryId),
                                member.cookie(),
                                member,
                                "foreign-hh-1"),
                        String.class)
                .getStatusCode()
                .value())
                .isEqualTo(404);
    }

    @Test
    void refusesWhilePasswordChangeRequired() {
        jdbc.update(
                "UPDATE app_user SET must_change_password = true, password_changed_at = null WHERE id = ?",
                memberId);
        Session member = login("member@example.com", "correct-password");
        assertThat(postExpense(
                        member,
                        """
                        {"effectiveDate":"2026-01-12","amount":"10","accountId":"%s","categoryId":"%s","beneficiaryId":"%s"}
                        """
                                .formatted(accountId, categoryId, beneficiaryId),
                        "temp-1")
                .getStatusCode()
                .value())
                .isEqualTo(403);
    }

    @Test
    void creatorReplacesAndAdminVoidsWithCompensation() {
        Session member = login("member@example.com", "correct-password");
        Session admin = login("admin@example.com", "correct-password");
        ResponseEntity<String> created = postExpense(
                member,
                """
                {"effectiveDate":"2026-01-12","amount":"250","accountId":"%s","categoryId":"%s","beneficiaryId":"%s","note":"Groceries"}
                """
                        .formatted(accountId, categoryId, beneficiaryId),
                "create-replace-1");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode createdBody = json.readTree(created.getBody());
        String expenseId = createdBody.get("id").asString();
        long version = createdBody.get("version").asLong();

        ResponseEntity<String> replaced = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses/" + expenseId,
                HttpMethod.PUT,
                jsonEntity(
                        """
                        {"effectiveDate":"2026-01-13","amount":"200","accountId":"%s","categoryId":"%s","beneficiaryId":"%s","note":"Adjusted","changeReason":"Price correction"}
                        """
                                .formatted(accountId, categoryId, beneficiaryId),
                        member.cookie(),
                        member,
                        "replace-1",
                        "\"" + version + "\""),
                String.class);
        assertThat(replaced.getStatusCode().value()).isEqualTo(200);
        JsonNode replacedBody = json.readTree(replaced.getBody());
        assertThat(replacedBody.get("amount").asString()).isEqualTo("200");
        assertThat(replacedBody.get("effectiveDate").asString()).isEqualTo("2026-01-13");
        assertThat(replacedBody.get("version").asLong()).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT COALESCE(SUM(signed_amount), 0)
                        FROM account_posting
                        WHERE household_id = ? AND account_id = ?
                        """,
                        BigDecimal.class,
                        householdId,
                        accountId))
                .isEqualByComparingTo("800");

        ResponseEntity<String> stale = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses/" + expenseId + "/void",
                HttpMethod.POST,
                jsonEntity(
                        "{\"reason\":\"stale attempt\"}",
                        admin.cookie(),
                        admin,
                        "void-stale-1",
                        "\"" + version + "\""),
                String.class);
        assertThat(stale.getStatusCode().value()).isEqualTo(412);

        ResponseEntity<String> missing = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses/" + expenseId + "/void",
                HttpMethod.POST,
                jsonEntity("{\"reason\":\"missing match\"}", admin.cookie(), admin, "void-missing-1", null),
                String.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(428);

        long currentVersion = replacedBody.get("version").asLong();
        ResponseEntity<String> voided = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses/" + expenseId + "/void",
                HttpMethod.POST,
                jsonEntity(
                        "{\"reason\":\"Duplicate purchase\"}",
                        admin.cookie(),
                        admin,
                        "void-1",
                        "\"" + currentVersion + "\""),
                String.class);
        assertThat(voided.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(voided.getBody()).get("state").asString()).isEqualTo("VOID");
        assertThat(json.readTree(voided.getBody()).get("capabilities").get("canVoid").asBoolean())
                .isFalse();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT COALESCE(SUM(signed_amount), 0)
                        FROM account_posting
                        WHERE household_id = ? AND account_id = ?
                        """,
                        BigDecimal.class,
                        householdId,
                        accountId))
                .isEqualByComparingTo("1000");

        ResponseEntity<String> replay = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses/" + expenseId + "/void",
                HttpMethod.POST,
                jsonEntity(
                        "{\"reason\":\"Duplicate purchase\"}",
                        admin.cookie(),
                        admin,
                        "void-1",
                        "\"" + currentVersion + "\""),
                String.class);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");

        ResponseEntity<String> doubleVoid = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses/" + expenseId + "/void",
                HttpMethod.POST,
                jsonEntity(
                        "{\"reason\":\"again\"}",
                        admin.cookie(),
                        admin,
                        "void-2",
                        "\"" + json.readTree(voided.getBody()).get("version").asLong() + "\""),
                String.class);
        assertThat(doubleVoid.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> list = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses",
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null, null),
                String.class);
        assertThat(json.readTree(list.getBody()).size()).isEqualTo(0);
    }

    @Test
    void memberCannotReplaceAnotherMembersExpense() {
        Session member = login("member@example.com", "correct-password");
        Session admin = login("admin@example.com", "correct-password");
        ResponseEntity<String> created = postExpense(
                admin,
                """
                {"effectiveDate":"2026-01-12","amount":"50","accountId":"%s","categoryId":"%s","beneficiaryId":"%s"}
                """
                        .formatted(accountId, categoryId, beneficiaryId),
                "admin-create-1");
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode body = json.readTree(created.getBody());
        ResponseEntity<String> forbidden = rest.exchange(
                "/api/v1/households/" + householdId + "/expenses/" + body.get("id").asString(),
                HttpMethod.PUT,
                jsonEntity(
                        """
                        {"effectiveDate":"2026-01-12","amount":"40","accountId":"%s","categoryId":"%s","beneficiaryId":"%s","changeReason":"nope"}
                        """
                                .formatted(accountId, categoryId, beneficiaryId),
                        member.cookie(),
                        member,
                        "member-replace-1",
                        "\"" + body.get("version").asLong() + "\""),
                String.class);
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
    }

    private ResponseEntity<String> postExpense(Session session, String body, String key) {
        return rest.exchange(
                "/api/v1/households/" + householdId + "/expenses",
                HttpMethod.POST,
                jsonEntity(body, session.cookie(), session, key),
                String.class);
    }

    private void insertAccount(UUID id, String name, String openedOn, String openingAmount) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Instant now = Instant.parse("2026-01-15T00:00:00Z");
            jdbc.update(
                    """
                    INSERT INTO account (
                        id, household_id, name, owner_label, type, currency_code, opened_on, status,
                        sort_order, version, balance_version, created_at, updated_at)
                    VALUES (?, ?, ?, 'Ada', 'CASH', 'MMK', ?, 'ACTIVE', 1, 0, 1, ?, ?)
                    """,
                    id,
                    householdId,
                    name,
                    java.sql.Date.valueOf(openedOn),
                    java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(now));
            UUID openingTx = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO financial_transaction (
                        id, household_id, kind, created_by_user_id, current_revision_no, version,
                        opening_account_id, created_at)
                    VALUES (?, ?, 'OPENING', ?, 1, 0, ?, ?)
                    """,
                    openingTx,
                    householdId,
                    adminId,
                    id,
                    java.sql.Timestamp.from(now));
            jdbc.update(
                    """
                    INSERT INTO transaction_revision (
                        household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                        effective_date, amount, currency_code, account_id, source_account_id,
                        destination_account_id, category_id, beneficiary_id, note, change_reason,
                        recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot,
                        category_name_snapshot, beneficiary_name_snapshot)
                    VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, ?, ?, 'MMK', ?, NULL, NULL, NULL, NULL, NULL, NULL,
                            ?, ?, ?, 'Admin', NULL, NULL)
                    """,
                    householdId,
                    openingTx,
                    java.sql.Date.valueOf(openedOn),
                    new BigDecimal(openingAmount),
                    id,
                    adminId,
                    java.sql.Timestamp.from(now),
                    name);
            if (new BigDecimal(openingAmount).compareTo(BigDecimal.ZERO) != 0) {
                jdbc.update(
                        """
                        INSERT INTO account_posting (
                            id, household_id, transaction_id, revision_no, account_id, effective_date,
                            currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                        VALUES (?, ?, ?, 1, ?, ?, 'MMK', ?, 1, 'APPLY', NULL, ?)
                        """,
                        UUID.randomUUID(),
                        householdId,
                        openingTx,
                        id,
                        java.sql.Date.valueOf(openedOn),
                        new BigDecimal(openingAmount),
                        java.sql.Timestamp.from(now));
            }
        });
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
        return jsonEntity(body, cookie, csrf, idempotencyKey, null);
    }

    private HttpEntity<String> jsonEntity(
            String body, String cookie, Session csrf, String idempotencyKey, String ifMatch) {
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
        if (ifMatch != null) {
            headers.add(HttpHeaders.IF_MATCH, ifMatch);
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
