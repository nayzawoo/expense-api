package com.nayzawoo.expense.health;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.endpoint.health.show-components=always",
            "management.endpoint.health.show-details=never"
        })
@AutoConfigureTestRestTemplate
@Testcontainers
class HealthEndpointIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    @Test
    void healthIsUpAfterBaselineMigration() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
        assertThat(response.getBody()).contains("\"api\":{\"status\":\"UP\"");
        assertThat(response.getBody()).contains("\"db\":{\"status\":\"UP\"");

        Long applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Long.class);
        assertThat(applied).isEqualTo(1L);
    }

    @Test
    void jpaValidatesFlywaySchemaWithoutCreatingApplicationTables() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(entity -> entity.getName())
                .containsExactlyInAnyOrder("Household", "AppUser", "Account", "Category", "Beneficiary");
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false");

        Long identityMigration = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success",
                Long.class);
        assertThat(identityMigration).isEqualTo(1L);
        Long ledgerMigration = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '3' and success",
                Long.class);
        assertThat(ledgerMigration).isEqualTo(1L);
        Long openingIntegrityMigration = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '4' and success",
                Long.class);
        assertThat(openingIntegrityMigration).isEqualTo(1L);
        Long identityAuditMigration = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '5' and success",
                Long.class);
        assertThat(identityAuditMigration).isEqualTo(1L);
        Long metadataMigration = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '6' and success",
                Long.class);
        assertThat(metadataMigration).isEqualTo(1L);
        Long expenseMigration = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '7' and success",
                Long.class);
        assertThat(expenseMigration).isEqualTo(1L);
        Long expenseMutationMigration = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '8' and success",
                Long.class);
        assertThat(expenseMutationMigration).isEqualTo(1L);

        Integer identityTables = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name IN (
                    'household', 'app_user', 'spring_session', 'spring_session_attributes', 'audit_event')
                """,
                Integer.class);
        assertThat(identityTables).isEqualTo(5);
        Integer ledgerTables = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name IN (
                    'account', 'financial_transaction', 'transaction_revision',
                    'account_posting', 'idempotency_record')
                """,
                Integer.class);
        assertThat(ledgerTables).isEqualTo(5);
        Integer metadataTables = jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name IN ('category', 'beneficiary')
                """,
                Integer.class);
        assertThat(metadataTables).isEqualTo(2);
    }
}
