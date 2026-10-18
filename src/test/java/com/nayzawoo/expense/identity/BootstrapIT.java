package com.nayzawoo.expense.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class BootstrapIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private BootstrapService bootstrapService;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock clock;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearIdentity() {
        when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM household");
    }

    @Test
    void bootstrapCreatesRestrictedAdminAndRefusesASecondRun() {
        BootstrapService.BootstrapResult result =
                bootstrapService.bootstrap("Home", "Admin@Example.com", "Admin", "bootstrap-password");

        assertThat(result.adminEmail()).isEqualTo("admin@example.com");
        assertThat(jdbc.queryForObject("select count(*) from household", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        select currency || ':' || currency_scale || ':' || timezone
                        from household where id = ?
                        """,
                        String.class,
                        result.householdId()))
                .isEqualTo("MMK:0:Asia/Yangon");
        assertThat(jdbc.queryForObject(
                        """
                        select role || ':' || status || ':' || must_change_password::text
                        from app_user where id = ?
                        """,
                        String.class,
                        result.adminUserId()))
                .isEqualTo("ADMIN:ACTIVE:true");
        assertThat(jdbc.queryForObject(
                        "select password_changed_at from app_user where id = ?",
                        Instant.class,
                        result.adminUserId()))
                .isNull();

        Session login = login("admin@example.com", "bootstrap-password");
        assertThat(login.body().get("mustChangePassword").asBoolean()).isTrue();
        ResponseEntity<String> household = rest.exchange(
                "/api/v1/household", HttpMethod.GET, jsonEntity(null, login.cookie(), null), String.class);
        assertThat(household.getStatusCode().value()).isEqualTo(403);

        assertThatThrownBy(() ->
                        bootstrapService.bootstrap("Other", "other@example.com", "Other", "bootstrap-password"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        assertThat(jdbc.queryForObject("select count(*) from household", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from app_user", Long.class)).isEqualTo(1L);
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

    private record Session(String cookie, String setCookie, String headerName, String token, JsonNode body) {}
}
