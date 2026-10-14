# expense-api

HTTP API for household expense tracking. This repository currently contains the Spring Boot foundation: a Java 21 build, PostgreSQL, Flyway, and a health check.

Authentication and the financial ledger are not implemented yet.

## Requirements

- Java 21
- Docker, for the local database and integration tests

## Local database

Start PostgreSQL:

```sh
docker compose up -d
```

The Compose service uses database `expense`, user `expense`, and password `local-dev-only`. Those values are local defaults. Set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` when running anywhere else.

Stop it with `docker compose down`. Add `-v` to remove the local data volume.

## Run

```sh
./mvnw spring-boot:run
```

Health check: <http://localhost:8080/actuator/health>

The response reports overall status only.

## Test

```sh
./mvnw verify
```

The integration test starts its own PostgreSQL container. Docker must be running. The test does not use the Compose database.

## Layout

Application code lives under `com.nayzawoo.expense`, with one package per feature. Flyway migrations live in `src/main/resources/db/migration`. The baseline migration does not create business tables.
