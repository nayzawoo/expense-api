package com.nayzawoo.expense.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class HealthProbesIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void databaseOutageFailsReadinessButNotLivenessAndRecovers() {
        assertHealth("readiness", HttpStatus.OK, "UP");
        assertHealth("liveness", HttpStatus.OK, "UP");
        String containerId = postgres.getContainerId();
        try {
            postgres.getDockerClient().pauseContainerCmd(containerId).exec();
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                assertHealth("readiness", HttpStatus.SERVICE_UNAVAILABLE, "DOWN");
                assertHealth("liveness", HttpStatus.OK, "UP");
            });
        } finally {
            postgres.getDockerClient().unpauseContainerCmd(containerId).exec();
        }
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertHealth("readiness", HttpStatus.OK, "UP");
            assertHealth("liveness", HttpStatus.OK, "UP");
        });
    }

    @Test
    void diagnosticsAreNotExposedAndHealthDoesNotLeakDetails() {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"")
                .doesNotContain("components", "details", "jdbc:", "PostgreSQL");
        for (String endpoint : new String[] {"env", "configprops", "beans", "metrics", "heapdump"}) {
            assertThat(restTemplate.getForEntity("/actuator/" + endpoint, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    private void assertHealth(String group, HttpStatus status, String health) {
        String path = "/actuator/health" + (group.isEmpty() ? "" : "/" + group);
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo("{\"status\":\"" + health + "\"}");
    }
}
