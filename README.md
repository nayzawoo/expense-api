# Expense API

A Java 21 / Spring Boot 4.1.1 backend foundation using Spring MVC, JPA validation,
Flyway and PostgreSQL 18.6. One household, session login, and password change are
implemented. Financial entries and the browser application are not.

Session login uses `GET /api/v1/auth/csrf`, `POST /api/v1/auth/login`, `GET /api/v1/auth/me`,
and `POST /api/v1/auth/logout`. Login, logout, and `PUT /api/v1/password` require the CSRF
header from the bootstrap response. The session cookie is `expense-session`, HttpOnly, and
SameSite Lax. Idle timeout is 7 days, and a login ends 30 days after it starts. A user who
must change their password can call only the CSRF, current-user, password-change, and logout
routes. A successful password change revokes every session for that user.

Create the first household and admin with a one-shot process start. Supply the admin password
only at runtime. Do not commit a password. The command refuses to run when a household already
exists, and a later ordinary start does not rotate the password:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
--expense.bootstrap.enabled=true \
--expense.bootstrap.household-name=Home \
--expense.bootstrap.admin-email=admin@example.com \
--expense.bootstrap.admin-display-name=Admin \
--expense.bootstrap.admin-password=$BOOTSTRAP_ADMIN_PASSWORD"
```

## Local containers

Install Docker with Compose v2 and start its engine, then run:

```bash
docker compose up --build -d --wait
```

Compose starts a non-root API with a read-only filesystem and writable temporary
storage, plus PostgreSQL on an internal Docker network. Neither service publishes
host ports. This backend-only setup has no browser-facing entry point yet.
The API connects to `postgres:5432` by container service name.

Inspect health from inside the API container:

```bash
docker compose exec api curl --fail --silent http://localhost:8080/actuator/health/readiness
docker compose exec api curl --fail --silent http://localhost:8080/actuator/health/liveness
docker compose ps
```

Healthy probes return HTTP 200 with `{"status":"UP"}`. Readiness includes the database
and returns HTTP 503 while PostgreSQL is unavailable. Liveness checks application
availability only, so a database outage does not cause a running API to fail liveness.
The API container healthcheck uses readiness; Compose reports it unhealthy during an
outage but does not automatically restart it. After PostgreSQL returns, readiness
recovers without restarting the API. Other Actuator endpoints and health details
are not exposed. These health endpoints are for private container access.

Stop without removing data:

```bash
docker compose down
```

Do not use `down -v` or prune volumes if you want to retain the database.

## Configuration

Compose uses database `expense`, user `expense`, and the synthetic password
`local-dev-only`. These defaults are for local development only. Set
`DATABASE_PASSWORD` in your shell or an untracked `.env` file to change it for a new
database; both services receive the same value. On an initialized volume, changing
this variable does not change PostgreSQL's stored password. Keep it consistent with
the existing database credentials; rotate credentials separately if required.
Never commit real credentials or `.env` files. Credentials are provided at runtime,
not built into the API image.

Outside Compose, the application accepts `DATABASE_URL`, `DATABASE_USERNAME` and
`DATABASE_PASSWORD`. The default URL is `jdbc:postgresql://localhost:5432/expense`;
it requires a separately provisioned development database. The Compose database is
not host-accessible. Do not add host port mappings to work around this isolation.
Database connection/validation timeouts are bounded for responsive readiness checks.

## Existing PostgreSQL data

The Compose project name remains `expense-api`, the volume key remains
`expense-postgres`, and its mount remains `/var/lib/postgresql`. Under the default
project name, the existing volume is `expense-api_expense-postgres`. PostgreSQL 18
stores its data in `/var/lib/postgresql/18/docker` within that mount.
See the [official PostgreSQL image data-directory guidance](https://hub.docker.com/_/postgres).

Before updating an existing setup:

1. Keep the same Compose project name (`-p` / `COMPOSE_PROJECT_NAME`, if previously
   customized), volume and database credentials. Inspect the existing mount with
   `docker inspect "$(docker compose ps -q postgres)"` and identify its source volume.
   Do not start a replacement against an uncertain volume or directory layout.
2. With the original database still running, take a logical backup:
   `docker compose exec -T postgres pg_dump -U expense -d expense -Fc > expense-backup.dump`.
   Store backups outside the repository, with restricted access, and verify a restore
   into a separate disposable database before relying on them.
3. Stop the original containers with `docker compose down` (without `-v`), then start
   this configuration using the same project name. Verify health and expected data.

No volume rename, directory move or database upgrade is performed here. If existing
data comes from PostgreSQL 17 or earlier, a different `PGDATA`, or an externally
named volume, stop and plan a backed-up migration to a **separate new volume** using
dump/restore or the supported PostgreSQL upgrade procedure. Do not point PostgreSQL
18 at an older cluster or silently move files. Keep the original volume and backup
until the restored data is verified. Changing mount/project names can select an
empty volume and make existing data appear lost even when the original still exists.

## Tests and CI

With Java 21 and Docker running:

```bash
./mvnw --batch-mode --no-transfer-progress verify
python3 scripts/container-smoke.py
```

Testcontainers creates disposable PostgreSQL databases for migration integrity,
JPA validation, health privacy and database-outage/readiness/liveness/recovery tests.
The smoke test builds and starts a randomly named isolated Compose project. It
checks network and port isolation, non-root/read-only runtime, restricted Actuator
exposure, container health transitions and persistence across container recreation.
It deletes only its own disposable project volume. Python 3 and Docker Compose v2
are required. GitHub Actions runs both commands on pull requests and main.

CI also installs Trivy 0.76.0 from a SHA-256-verified release archive and scans
resolved compile and runtime dependency JARs (Maven Dependency Plugin 3.8.1) and
the actual locally built runtime image, including OS and bundled Java packages.
Dependency archives use Trivy's `rootfs` mode with library packages only; its
`filesystem` mode does not analyze JAR archives.
The Docker build reuses the smoke-test build cache; Maven tests and Compose smoke
run once. HIGH and CRITICAL findings, including unfixed findings, block the gate
pending applicability review and a separately reviewed remediation. No findings
are automatically suppressed. Lower and unknown severities remain in reports for
triage. Scan/download errors, malformed reports and absent Java/OS package coverage
also fail. Controlled policy tests exercise severe findings and scanner failures.
The final `ci/gate` fails on failed, skipped or canceled verification.

JSON scan reports are retained as Actions artifacts for seven days; concise
package/version/finding summaries appear in job logs. Scanner version and binary
digest are pinned; vulnerability and Java databases update at scan time, so findings
can change between runs. JAR identification and advisory databases have coverage
limits; a passing scan does not establish exploitability or absence of all flaws.
Maven build plugins, test-only libraries, host tools, the database service image and
the intermediate build image are outside these two scan targets. There is no deployment or automatic
dependency/image upgrade in this workflow.

Flyway owns DDL; Hibernate validates and Open Session in View is disabled. The
baseline migration contains no business tables. Container builds use pinned Java 21
base images and the Maven Wrapper; tests run separately rather than inside the image
build. This setup provides no production deployment.
