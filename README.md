# Expense API

**A Spring Boot backend foundation for household expense tracking.**

[![CI](https://github.com/nayzawoo/expense-api/actions/workflows/ci.yml/badge.svg)](https://github.com/nayzawoo/expense-api/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot 4.1.1](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL 18](https://img.shields.io/badge/PostgreSQL-18-4169E1?logo=postgresql&logoColor=white)

A clean, testable starting point for a household finance API. The current implementation focuses on the application runtime, database connectivity, migrations, and automated verification.

> **Project status:** Foundation in place. Authentication, accounts, expenses, transfers, and the financial ledger are **not implemented yet**.

## Tech stack

| Area | Technology |
| --- | --- |
| Language & framework | Java 21 · Spring Boot 4.1.1 · Spring MVC |
| Persistence | Spring Data JPA · Hibernate (validate only) |
| Database | PostgreSQL 18 · Flyway |
| Local development | Docker Compose · Maven Wrapper |
| Testing | JUnit · Spring Boot Test · Testcontainers |
| Continuous integration | GitHub Actions · `./mvnw verify` |

## Quick start

**Prerequisites:** Java 21 and Docker with the engine running. Ensure ports `5432` and `8080` are available.

**1. Start PostgreSQL**

```bash
docker compose up -d
```

**2. Run the API**

```bash
./mvnw spring-boot:run
```

**3. Check application health**

```bash
curl --fail http://localhost:8080/actuator/health
```

A healthy application returns HTTP `200` with `"status": "UP"`. The health endpoint does not expose detailed component information by default.

## Configuration

The application reads its database settings from environment variables, with defaults for **local development only**.

| Variable | Local default |
| --- | --- |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/expense` |
| `DATABASE_USERNAME` | `expense` |
| `DATABASE_PASSWORD` | `local-dev-only` |

These values match `compose.yaml` and are **not suitable for production**. Supply your own credentials and database URL outside local development. Do not commit real credentials or `.env` files.

Stop the local database with `docker compose down`. To delete its stored data, run `docker compose down -v`.

## Testing & CI

```bash
./mvnw verify
```

Maven runs the configured test phases, including the PostgreSQL integration test. **Docker must be running**, but you do not need to start the Compose database first: Testcontainers creates its own PostgreSQL container.

The integration test verifies the health endpoint and confirms that Flyway applied the baseline migration.

[![CI](https://github.com/nayzawoo/expense-api/actions/workflows/ci.yml/badge.svg)](https://github.com/nayzawoo/expense-api/actions/workflows/ci.yml) **GitHub Actions** runs the same Maven verification on pull requests and pushes to `main`. The badge reflects the workflow status for the default branch once CI is merged.

## Project structure

```text
src/
├── main/
│   ├── java/com/nayzawoo/expense/
│   │   ├── ExpenseApiApplication.java
│   │   └── health/ApiHealthIndicator.java
│   └── resources/
│       ├── application.yml
│       └── db/migration/V1__baseline.sql
└── test/
    └── java/com/nayzawoo/expense/health/HealthEndpointIT.java

compose.yaml                  # Local PostgreSQL
pom.xml                       # Build and dependencies
.github/workflows/ci.yml      # Automated verification
```

The baseline Flyway migration intentionally creates **no business tables**. Hibernate runs with `ddl-auto=validate` and Open Session in View disabled; Flyway is the only component allowed to change the database schema. The PostgreSQL integration tests verify the JPA factory initializes without creating tables. Domain and security features will be added in focused, tested changes.
