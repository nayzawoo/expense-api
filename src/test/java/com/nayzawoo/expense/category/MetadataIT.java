package com.nayzawoo.expense.category;

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
class MetadataIT {

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
                TRUNCATE category, beneficiary, audit_event, spring_session_attributes, spring_session,
                         app_user, household
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
    void lucideCatalogKeysRoundTripAndUnknownNamesAreRejected() {
        Session admin = login("admin@example.com", "correct-password");
        String path = "/api/v1/households/" + householdId + "/categories";
        ResponseEntity<String> created = rest.exchange(
                path, HttpMethod.POST,
                jsonEntity("{\"name\":\"Fruit\",\"color\":\"#112233\",\"iconKey\":\"lucide:apple\",\"sortOrder\":1}",
                        admin.cookie(), admin), String.class);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode body = json.readTree(created.getBody());
        assertThat(body.get("iconKey").asString()).isEqualTo("lucide:apple");
        ResponseEntity<String> updated = rest.exchange(
                path + "/" + body.get("id").asString(), HttpMethod.PUT,
                jsonEntity("{\"iconKey\":\"lucide:landmark\"}", admin.cookie(), admin), String.class);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(updated.getBody()).get("iconKey").asString()).isEqualTo("lucide:landmark");
        for (String key : new String[] {"lucide:not-a-real-icon", "lucide:../apple", "lucide:Apple"}) {
            ResponseEntity<String> rejected = rest.exchange(
                    path + "/" + body.get("id").asString(), HttpMethod.PUT,
                    jsonEntity("{\"iconKey\":\"" + key + "\"}", admin.cookie(), admin), String.class);
            assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Test
    void adminManagesCategoriesAndBeneficiariesWhileMembersRead() {
        Session admin = login("admin@example.com", "correct-password");
        Session member = login("member@example.com", "correct-password");

        ResponseEntity<String> createdCategory = rest.exchange(
                "/api/v1/households/" + householdId + "/categories",
                HttpMethod.POST,
                jsonEntity(
                        "{\"name\":\"Food\",\"color\":\"#aabbcc\",\"iconKey\":\"food\",\"sortOrder\":1}",
                        admin.cookie(),
                        admin),
                String.class);
        assertThat(createdCategory.getStatusCode().value()).isEqualTo(201);
        JsonNode categoryBody = json.readTree(createdCategory.getBody());
        assertThat(categoryBody.get("color").asString()).isEqualTo("#AABBCC");
        String categoryId = categoryBody.get("id").asString();

        ResponseEntity<String> duplicate = rest.exchange(
                "/api/v1/households/" + householdId + "/categories",
                HttpMethod.POST,
                jsonEntity(
                        "{\"name\":\" food \",\"color\":\"#112233\",\"iconKey\":\"other\",\"sortOrder\":2}",
                        admin.cookie(),
                        admin),
                String.class);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> memberCreate = rest.exchange(
                "/api/v1/households/" + householdId + "/categories",
                HttpMethod.POST,
                jsonEntity(
                        "{\"name\":\"Travel\",\"color\":\"#112233\",\"iconKey\":\"transport\",\"sortOrder\":2}",
                        member.cookie(),
                        member),
                String.class);
        assertThat(memberCreate.getStatusCode().value()).isEqualTo(403);

        ResponseEntity<String> memberList = rest.exchange(
                "/api/v1/households/" + householdId + "/categories",
                HttpMethod.GET,
                jsonEntity(null, member.cookie(), null),
                String.class);
        assertThat(memberList.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(memberList.getBody()).size()).isEqualTo(1);

        ResponseEntity<String> renamed = rest.exchange(
                "/api/v1/households/" + householdId + "/categories/" + categoryId,
                HttpMethod.PUT,
                jsonEntity("{\"name\":\"Groceries\"}", admin.cookie(), admin),
                String.class);
        assertThat(renamed.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(renamed.getBody()).get("name").asString()).isEqualTo("Groceries");

        ResponseEntity<String> archived = rest.exchange(
                "/api/v1/households/" + householdId + "/categories/" + categoryId + "/status",
                HttpMethod.PATCH,
                jsonEntity("{\"status\":\"ARCHIVED\"}", admin.cookie(), admin),
                String.class);
        assertThat(archived.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(archived.getBody()).get("status").asString()).isEqualTo("ARCHIVED");

        ResponseEntity<String> createdBeneficiary = rest.exchange(
                "/api/v1/households/" + householdId + "/beneficiaries",
                HttpMethod.POST,
                jsonEntity("{\"name\":\"Mina\",\"kind\":\"PERSON\",\"sortOrder\":2}", admin.cookie(), admin),
                String.class);
        assertThat(createdBeneficiary.getStatusCode().value()).isEqualTo(201);
        String beneficiaryId = json.readTree(createdBeneficiary.getBody()).get("id").asString();

        ResponseEntity<String> renamedBeneficiary = rest.exchange(
                "/api/v1/households/" + householdId + "/beneficiaries/" + beneficiaryId,
                HttpMethod.PUT,
                jsonEntity("{\"name\":\"Mina Aung\"}", admin.cookie(), admin),
                String.class);
        assertThat(renamedBeneficiary.getStatusCode().value()).isEqualTo(200);

        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_event where event_type = 'CATEGORY_CREATED'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_event where event_type = 'CATEGORY_RENAMED'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        "select count(*) from audit_event where event_type = 'BENEFICIARY_RENAMED'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                        """
                        select details::text from audit_event where event_type = 'BENEFICIARY_RENAMED'
                        """,
                        String.class))
                .doesNotContain("correct-password");
    }

    @Test
    void adminAcceptsSupportedCategoryIconsAndRejectsUnknownKeys() {
        Session admin = login("admin@example.com", "correct-password");
        String[] iconKeys = {
            "food",
            "transport",
            "home",
            "health",
            "shopping",
            "bills",
            "education",
            "entertainment",
            "other",
            "groceries",
            "dining",
            "coffee",
            "car",
            "fuel",
            "travel",
            "utilities",
            "phone",
            "internet",
            "rent",
            "clothing",
            "beauty",
            "fitness",
            "pets",
            "gifts",
            "family",
            "work",
            "insurance",
            "savings",
            "charity",
            "subscriptions"
        };
        assertThat(iconKeys).hasSize(30);

        ResponseEntity<String> createdGroceries = rest.exchange(
                "/api/v1/households/" + householdId + "/categories",
                HttpMethod.POST,
                jsonEntity(
                        "{\"name\":\"Groceries\",\"color\":\"#AABBCC\",\"iconKey\":\"groceries\",\"sortOrder\":1}",
                        admin.cookie(),
                        admin),
                String.class);
        assertThat(createdGroceries.getStatusCode().value()).isEqualTo(201);
        JsonNode groceriesBody = json.readTree(createdGroceries.getBody());
        assertThat(groceriesBody.get("iconKey").asString()).isEqualTo("groceries");
        String groceriesId = groceriesBody.get("id").asString();

        ResponseEntity<String> updatedDining = rest.exchange(
                "/api/v1/households/" + householdId + "/categories/" + groceriesId,
                HttpMethod.PUT,
                jsonEntity("{\"iconKey\":\"dining\"}", admin.cookie(), admin),
                String.class);
        assertThat(updatedDining.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(updatedDining.getBody()).get("iconKey").asString()).isEqualTo("dining");

        for (int i = 0; i < iconKeys.length; i++) {
            ResponseEntity<String> created = rest.exchange(
                    "/api/v1/households/" + householdId + "/categories",
                    HttpMethod.POST,
                    jsonEntity(
                            "{\"name\":\"Icon %d\",\"color\":\"#112233\",\"iconKey\":\"%s\",\"sortOrder\":%d}"
                                    .formatted(i, iconKeys[i], i),
                            admin.cookie(),
                            admin),
                    String.class);
            assertThat(created.getStatusCode().value()).isEqualTo(201);
            assertThat(json.readTree(created.getBody()).get("iconKey").asString()).isEqualTo(iconKeys[i]);
        }

        ResponseEntity<String> invalidCreate = rest.exchange(
                "/api/v1/households/" + householdId + "/categories",
                HttpMethod.POST,
                jsonEntity(
                        "{\"name\":\"Unknown\",\"color\":\"#AABBCC\",\"iconKey\":\"unknown\",\"sortOrder\":99}",
                        admin.cookie(),
                        admin),
                String.class);
        assertThat(invalidCreate.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> invalidUpdate = rest.exchange(
                "/api/v1/households/" + householdId + "/categories/" + groceriesId,
                HttpMethod.PUT,
                jsonEntity("{\"iconKey\":\"unknown\"}", admin.cookie(), admin),
                String.class);
        assertThat(invalidUpdate.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<String> unchanged = rest.exchange(
                "/api/v1/households/" + householdId + "/categories/" + groceriesId,
                HttpMethod.GET,
                jsonEntity(null, admin.cookie(), null),
                String.class);
        assertThat(unchanged.getStatusCode().value()).isEqualTo(200);
        assertThat(json.readTree(unchanged.getBody()).get("iconKey").asString()).isEqualTo("dining");
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
