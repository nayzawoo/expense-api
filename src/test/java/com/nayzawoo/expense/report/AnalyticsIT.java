package com.nayzawoo.expense.report;

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
class AnalyticsIT {

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
    private UUID accountA;
    private UUID accountB;
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
        accountA = UUID.randomUUID();
        accountB = UUID.randomUUID();
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
        Instant meta = Instant.parse("2026-01-15T00:00:00Z");
        jdbc.update(
                """
                INSERT INTO category (
                    id, household_id, name, color, icon_key, status, sort_order, version, created_at, updated_at)
                VALUES (?, ?, 'Food', '#AABBCC', 'food', 'ACTIVE', 1, 0, ?, ?)
                """,
                categoryId,
                householdId,
                java.sql.Timestamp.from(meta),
                java.sql.Timestamp.from(meta));
        jdbc.update(
                """
                INSERT INTO beneficiary (
                    id, household_id, name, kind, status, sort_order, version, created_at, updated_at)
                VALUES (?, ?, 'Ada', 'PERSON', 'ACTIVE', 1, 0, ?, ?)
                """,
                beneficiaryId,
                householdId,
                java.sql.Timestamp.from(meta),
                java.sql.Timestamp.from(meta));
        insertAccount(accountA, "Cash", "2026-01-01", "1000");
        insertAccount(accountB, "Bank", "2026-01-01", "0");
        insertIncome(accountA, "200", "2026-01-05");
        insertExpense(accountA, "50", "2026-01-06");
        insertTransfer(accountA, accountB, "100", "2026-01-07");
        insertAdjustment(accountA, "-10", "2026-01-08");
    }

    @Test
    void analyticsReportsCanonicalTotalsAndPartialMonthlyTrend() {
        Session member = login("member@example.com", "correct-password");
        ResponseEntity<String> result = rest.exchange(
                "/api/v1/households/" + householdId + "/reports/analytics?asOf=2026-01-15",
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null),
                String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(result.getBody());
        assertThat(body.get("overview").get("income").asString()).isEqualTo("200");
        assertThat(body.get("overview").get("expense").asString()).isEqualTo("50");
        assertThat(body.get("overview").get("net").asString()).isEqualTo("150");
        assertThat(body.get("overview").get("previousFiveMonthAverage").isNull()).isTrue();
        assertThat(body.get("overview").get("elapsedDayAverage").asString()).isEqualTo("3");
        assertThat(body.get("overview").get("expenseDifference").isNull()).isTrue();
        assertThat(body.get("overview").get("direction").isNull()).isTrue();
        assertThat(body.get("period").get("coverage").get("complete").asBoolean()).isTrue();
        assertThat(body.get("comparisonPeriod").get("coverage").get("complete").asBoolean()).isFalse();
        assertThat(body.get("comparisonPeriod").get("coverage").get("reason").asString())
                .isEqualTo("ACCOUNT_OPENING_GAP");
        assertThat(body.get("balanceCoverage").get("from").asString()).isEqualTo("2026-01-01");
        assertThat(body.get("balanceCoverage").get("complete").asBoolean()).isTrue();
        assertThat(body.get("insight").isNull()).isTrue();
        assertThat(body.get("monthlyTrend").size()).isEqualTo(6);
        JsonNode current = body.get("monthlyTrend").get(5);
        assertThat(current.get("month").asString()).isEqualTo("2026-01");
        assertThat(current.get("isPartial").asBoolean()).isTrue();
        assertThat(current.get("coverage").asString()).isEqualTo("COMPLETE");
        assertThat(current.get("expense").asString()).isEqualTo("50");
        JsonNode prior = body.get("monthlyTrend").get(0);
        assertThat(prior.get("month").asString()).isEqualTo("2025-08");
        assertThat(prior.get("coverage").asString()).isEqualTo("UNCOVERED");
        assertThat(prior.get("expense").isNull()).isTrue();
        assertThat(body.get("categoryBreakdown").get(0).get("amount").asString()).isEqualTo("50");

        ResponseEntity<String> future = rest.exchange(
                "/api/v1/households/" + householdId + "/reports/analytics?asOf=2026-01-16",
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null),
                String.class);
        assertThat(future.getStatusCode().value()).isEqualTo(422);

        ResponseEntity<String> foreign = rest.exchange(
                "/api/v1/households/" + UUID.randomUUID() + "/reports/analytics",
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null),
                String.class);
        assertThat(foreign.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void laterOpeningNullsElapsedDayAverageOnAnalytics() {
        jdbc.update(
                "UPDATE account SET opened_on = ? WHERE id = ?",
                java.sql.Date.valueOf("2026-01-05"),
                accountB);
        Session member = login("member@example.com", "correct-password");
        ResponseEntity<String> result = rest.exchange(
                "/api/v1/households/" + householdId + "/reports/analytics?asOf=2026-01-15",
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null),
                String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        JsonNode body = json.readTree(result.getBody());
        assertThat(body.get("period").get("coverage").get("complete").asBoolean()).isFalse();
        assertThat(body.get("period").get("coverage").get("reason").asString())
                .isEqualTo("ACCOUNT_OPENING_GAP");
        assertThat(body.get("overview").get("elapsedDayAverage").isNull()).isTrue();
        assertThat(body.get("overview").get("expenseDifference").isNull()).isTrue();
        assertThat(body.get("overview").get("direction").isNull()).isTrue();
    }

    private void insertExpense(UUID accountId, String amount, String effectiveDate) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Instant recordedAt = Instant.parse(effectiveDate + "T12:00:00Z");
            UUID tx = UUID.randomUUID();
            BigDecimal value = new BigDecimal(amount);
            jdbc.update(
                    """
                    INSERT INTO financial_transaction (
                        id, household_id, kind, created_by_user_id, current_revision_no, version,
                        opening_account_id, created_at)
                    VALUES (?, ?, 'EXPENSE', ?, 1, 0, NULL, ?)
                    """,
                    tx,
                    householdId,
                    memberId,
                    java.sql.Timestamp.from(recordedAt));
            jdbc.update(
                    """
                    INSERT INTO transaction_revision (
                        household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                        effective_date, amount, currency_code, account_id, source_account_id,
                        destination_account_id, category_id, beneficiary_id, note, change_reason,
                        recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot,
                        category_name_snapshot, beneficiary_name_snapshot)
                    VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, ?, ?, 'MMK', ?, NULL, NULL, ?, ?, NULL, NULL,
                            ?, ?, 'Cash', 'Member', 'Food', 'Ada')
                    """,
                    householdId,
                    tx,
                    java.sql.Date.valueOf(effectiveDate),
                    value,
                    accountId,
                    categoryId,
                    beneficiaryId,
                    memberId,
                    java.sql.Timestamp.from(recordedAt));
            jdbc.update(
                    """
                    INSERT INTO account_posting (
                        id, household_id, transaction_id, revision_no, account_id, effective_date,
                        currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                    VALUES (?, ?, ?, 1, ?, ?, 'MMK', ?, 1, 'APPLY', NULL, ?)
                    """,
                    UUID.randomUUID(),
                    householdId,
                    tx,
                    accountId,
                    java.sql.Date.valueOf(effectiveDate),
                    value.negate(),
                    java.sql.Timestamp.from(recordedAt));
        });
    }

    private void insertIncome(UUID accountId, String amount, String effectiveDate) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Instant recordedAt = Instant.parse(effectiveDate + "T12:00:00Z");
            UUID tx = UUID.randomUUID();
            BigDecimal value = new BigDecimal(amount);
            jdbc.update(
                    """
                    INSERT INTO financial_transaction (
                        id, household_id, kind, created_by_user_id, current_revision_no, version,
                        opening_account_id, created_at)
                    VALUES (?, ?, 'INCOME', ?, 1, 0, NULL, ?)
                    """,
                    tx,
                    householdId,
                    memberId,
                    java.sql.Timestamp.from(recordedAt));
            jdbc.update(
                    """
                    INSERT INTO transaction_revision (
                        household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                        effective_date, amount, currency_code, account_id, source_account_id,
                        destination_account_id, category_id, beneficiary_id, note, change_reason,
                        recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot,
                        category_name_snapshot, beneficiary_name_snapshot)
                    VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, ?, ?, 'MMK', ?, NULL, NULL, NULL, ?, NULL, NULL,
                            ?, ?, 'Cash', 'Member', NULL, 'Ada')
                    """,
                    householdId,
                    tx,
                    java.sql.Date.valueOf(effectiveDate),
                    value,
                    accountId,
                    beneficiaryId,
                    memberId,
                    java.sql.Timestamp.from(recordedAt));
            jdbc.update(
                    """
                    INSERT INTO account_posting (
                        id, household_id, transaction_id, revision_no, account_id, effective_date,
                        currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                    VALUES (?, ?, ?, 1, ?, ?, 'MMK', ?, 1, 'APPLY', NULL, ?)
                    """,
                    UUID.randomUUID(),
                    householdId,
                    tx,
                    accountId,
                    java.sql.Date.valueOf(effectiveDate),
                    value,
                    java.sql.Timestamp.from(recordedAt));
        });
    }

    private void insertTransfer(UUID sourceId, UUID destId, String amount, String effectiveDate) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Instant recordedAt = Instant.parse(effectiveDate + "T12:00:00Z");
            UUID tx = UUID.randomUUID();
            BigDecimal value = new BigDecimal(amount);
            jdbc.update(
                    """
                    INSERT INTO financial_transaction (
                        id, household_id, kind, created_by_user_id, current_revision_no, version,
                        opening_account_id, created_at)
                    VALUES (?, ?, 'TRANSFER', ?, 1, 0, NULL, ?)
                    """,
                    tx,
                    householdId,
                    memberId,
                    java.sql.Timestamp.from(recordedAt));
            jdbc.update(
                    """
                    INSERT INTO transaction_revision (
                        household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                        effective_date, amount, currency_code, account_id, source_account_id,
                        destination_account_id, category_id, beneficiary_id, note, change_reason,
                        recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot,
                        category_name_snapshot, beneficiary_name_snapshot)
                    VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, ?, ?, 'MMK', NULL, ?, ?, NULL, NULL, NULL, NULL,
                            ?, ?, NULL, 'Member', NULL, NULL)
                    """,
                    householdId,
                    tx,
                    java.sql.Date.valueOf(effectiveDate),
                    value,
                    sourceId,
                    destId,
                    memberId,
                    java.sql.Timestamp.from(recordedAt));
            jdbc.update(
                    """
                    INSERT INTO account_posting (
                        id, household_id, transaction_id, revision_no, account_id, effective_date,
                        currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                    VALUES (?, ?, ?, 1, ?, ?, 'MMK', ?, 1, 'APPLY', NULL, ?)
                    """,
                    UUID.randomUUID(),
                    householdId,
                    tx,
                    sourceId,
                    java.sql.Date.valueOf(effectiveDate),
                    value.negate(),
                    java.sql.Timestamp.from(recordedAt));
            jdbc.update(
                    """
                    INSERT INTO account_posting (
                        id, household_id, transaction_id, revision_no, account_id, effective_date,
                        currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                    VALUES (?, ?, ?, 1, ?, ?, 'MMK', ?, 2, 'APPLY', NULL, ?)
                    """,
                    UUID.randomUUID(),
                    householdId,
                    tx,
                    destId,
                    java.sql.Date.valueOf(effectiveDate),
                    value,
                    java.sql.Timestamp.from(recordedAt));
        });
    }

    private void insertAdjustment(UUID accountId, String amount, String effectiveDate) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Instant recordedAt = Instant.parse(effectiveDate + "T12:00:00Z");
            UUID tx = UUID.randomUUID();
            BigDecimal value = new BigDecimal(amount);
            BigDecimal before = new BigDecimal("1050");
            BigDecimal observed = before.add(value);
            jdbc.update(
                    """
                    INSERT INTO financial_transaction (
                        id, household_id, kind, created_by_user_id, current_revision_no, version,
                        opening_account_id, created_at)
                    VALUES (?, ?, 'ADJUSTMENT', ?, 1, 0, NULL, ?)
                    """,
                    tx,
                    householdId,
                    adminId,
                    java.sql.Timestamp.from(recordedAt));
            jdbc.update(
                    """
                    INSERT INTO transaction_revision (
                        household_id, transaction_id, revision_no, operation, state, previous_revision_no,
                        effective_date, amount, currency_code, account_id, source_account_id,
                        destination_account_id, category_id, beneficiary_id, note, change_reason,
                        recorded_by_user_id, recorded_at, account_name_snapshot, actor_display_name_snapshot,
                        category_name_snapshot, beneficiary_name_snapshot,
                        balance_before, observed_balance, observed_balance_version)
                    VALUES (?, ?, 1, 'CREATE', 'POSTED', NULL, ?, ?, 'MMK', ?, NULL, NULL, NULL, NULL, NULL, 'adj',
                            ?, ?, 'Cash', 'Admin', NULL, NULL, ?, ?, 1)
                    """,
                    householdId,
                    tx,
                    java.sql.Date.valueOf(effectiveDate),
                    value,
                    accountId,
                    adminId,
                    java.sql.Timestamp.from(recordedAt),
                    before,
                    observed);
            jdbc.update(
                    """
                    INSERT INTO account_posting (
                        id, household_id, transaction_id, revision_no, account_id, effective_date,
                        currency_code, signed_amount, leg_no, role, reverses_posting_id, recorded_at)
                    VALUES (?, ?, ?, 1, ?, ?, 'MMK', ?, 1, 'APPLY', NULL, ?)
                    """,
                    UUID.randomUUID(),
                    householdId,
                    tx,
                    accountId,
                    java.sql.Date.valueOf(effectiveDate),
                    value,
                    java.sql.Timestamp.from(recordedAt));
        });
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
                    java.sql.Timestamp.from(Instant.parse(openedOn + "T00:00:00Z")),
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
                        java.sql.Timestamp.from(Instant.parse(openedOn + "T00:00:00Z")));
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
                        csrf),
                String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        String cookie = sessionCookie(result);
        String sessionCookieValue = cookie == null ? csrf.cookie() : cookie.split(";", 2)[0];
        Session refreshed = csrfFor(sessionCookieValue);
        return new Session(sessionCookieValue, refreshed.headerName(), refreshed.token());
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
        return new Session(sessionCookieValue, body.get("headerName").asString(), body.get("token").asString());
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

    private record Session(String cookie, String headerName, String token) {}
}
