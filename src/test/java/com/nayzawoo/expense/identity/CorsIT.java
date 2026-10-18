package com.nayzawoo.expense.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class CorsIT {

    private static final String ANGULAR_ORIGIN = "http://localhost:4200";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6");

    @Autowired
    private TestRestTemplate rest;

    @Test
    void preflightAllowsAngularOriginWithCredentialedHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, ANGULAR_ORIGIN);
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-csrf-token,idempotency-key");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/auth/login", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ANGULAR_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowHeaders())
                .anySatisfy(value -> assertThat(value.toLowerCase()).contains("x-csrf-token"))
                .anySatisfy(value -> assertThat(value.toLowerCase()).contains("idempotency-key"));
    }

    @Test
    void csrfResponseReflectsAllowedAngularOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, ANGULAR_ORIGIN);

        ResponseEntity<String> response =
                rest.exchange("/api/v1/auth/csrf", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ANGULAR_ORIGIN);
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
        assertThat(response.getBody()).contains("token");
    }

    @Test
    void disallowedOriginDoesNotReceiveAllowOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, "http://localhost:9999");

        ResponseEntity<String> response =
                rest.exchange("/api/v1/auth/csrf", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }
}
