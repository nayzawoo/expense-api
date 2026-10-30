# Expense API

Java 21 / Spring Boot household expense backend. Spring MVC, JPA, Flyway, and
PostgreSQL. Session cookie auth with CSRF (no bearer tokens).

## Requirements

- Java 21
- Docker with Compose v2 (for local database, containers, and integration tests)

## Quick start

```bash
make help          # List shortcuts
make up            # Build and start the container stack
make health        # Readiness / liveness
make down          # Stop containers (keeps database volume)
```

Local Spring against Compose Postgres only:

```bash
make run           # Postgres on 127.0.0.1:5432 + Spring on the host
```

First household / admin bootstrap and operator password recovery are one-shot
process flags. Passwords are supplied at runtime only — never commit them.

## Configuration

Runtime env (Compose defaults are local-dev only):

- `DATABASE_URL` (default `jdbc:postgresql://localhost:5432/expense`)
- `DATABASE_USERNAME`
- `DATABASE_PASSWORD`

Do not commit real credentials or `.env` files.

## Tests

```bash
make compile
make test                              # Unit tests
make test-focused TEST=ProfileIT       # Selected IT (Docker required)
make check                             # Full verify + container smoke
```

GitHub Actions runs verification and dependency/image scans on pull requests and
`main`.
