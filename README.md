# Expense API

A Java 21 / Spring Boot 4.1.1 backend foundation using Spring MVC, JPA validation,
Flyway and PostgreSQL 18.6. One household, session login, password change,
household user administration, profile self-service, admin account creation with
an immutable opening entry, category/beneficiary metadata, expense create,
list/detail, replace, and void, income create/list/detail/void, transfer
create/list/detail/void, admin reconciliation with adjustment list/detail/void,
member account ledger paging, member dashboard reads, and a member analytics core
read are implemented. The browser application is not.

Session login uses `GET /api/v1/auth/csrf`, `POST /api/v1/auth/login`, `GET /api/v1/auth/me`,
and `POST /api/v1/auth/logout`. There is no bearer or API-token login. Login, logout, and
`PUT /api/v1/password` require the CSRF header from the bootstrap response. Successful login
rotates the session id and replaces the anonymous CSRF token; callers must fetch a fresh CSRF
token before the next write. The session cookie is `expense-session`, HttpOnly, and SameSite
Lax. Browser clients must send credentials (`credentials: 'include'` / `withCredentials`) so
the session cookie is included. A login ends 30 days after it starts.

Local credentialed CORS is enabled for `http://localhost:4200` and `http://127.0.0.1:4200`
under `expense.cors.allowed-origins`. Allowed request headers include `X-CSRF-TOKEN`,
`Idempotency-Key`, and `If-Match`. Set that list to empty (or omit non-local origins) when
cross-origin browser access is not intended.
Logical idle expiry is 7 days from the last qualifying activity and is separate from JDBC
last-access; `POST /api/v1/auth/activity` renews that idle deadline when CSRF-authenticated and
not under the temporary-password restriction. Passive `GET /auth/me` and CSRF bootstrap do not
renew it. Session storage timeout is 30 days so framework last-access cannot discard a still-valid
absolute window early. A user who must change their password can call only the CSRF,
current-user, password-change, and logout routes. A successful password change revokes every
session for that user. Temporary-password reset, password rotation, operator recovery, and
login-email changes write identity audit events without storing credentials.

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

An admin may list, create, and update household users under
`/api/v1/households/{householdId}/users`, including temporary-password reset for
another user. Role and status changes take an exclusive household lock and recheck
the actor before mutating. Disabling a user increments `security_version` and
revokes that user's sessions. The last active admin cannot be disabled or demoted.
Authenticated requests reload the actor so role, status, and security-version
changes apply on the next request.

Signed-in users may call `GET /api/v1/profile` and `PUT /api/v1/profile` after the
forced password change is complete. Changing email requires the current password
and revokes every session for that user.

An admin may create accounts under `/api/v1/households/{householdId}/accounts` with
an `Idempotency-Key`. Creation is atomic with the immutable opening revision and
postings. Active household members may list and read account balances derived from
postings as of the household's current local date.

Active members may list and read categories and beneficiaries under
`/api/v1/households/{householdId}/categories` and
`/api/v1/households/{householdId}/beneficiaries`. Admins create, update, and
archive those references. Category `iconKey` values are `food`, `transport`,
`home`, `health`, `shopping`, `bills`, `education`, `entertainment`, `other`,
`groceries`, `dining`, `coffee`, `car`, `fuel`, `travel`, `utilities`, `phone`,
`internet`, `rent`, `clothing`, `beauty`, `fitness`, `pets`, `gifts`, `family`,
`work`, `insurance`, `savings`, `charity`, and `subscriptions`. Unsupported
keys are rejected. Bootstrap seeds collective `Household` and `Other`
beneficiaries. Bulk order ETag routes are not included yet.

Active members may create expenses under
`/api/v1/households/{householdId}/expenses` with an `Idempotency-Key`. Creation
writes an immutable EXPENSE revision and a debit posting, and increments the
account `balance_version`. Members may list and read current POSTED expenses.
The creator or an admin may replace or void a POSTED expense with `If-Match` and
an `Idempotency-Key`; compensation postings retain history.

Active members may create incomes under
`/api/v1/households/{householdId}/incomes` with an `Idempotency-Key`. Creation
writes an immutable INCOME revision and a credit posting (no category). Members
may list and read current POSTED incomes. The creator or an admin may void a
POSTED income with `If-Match` and an `Idempotency-Key`. Income has no replace
route.

Active members may create transfers under
`/api/v1/households/{householdId}/transfers` with an `Idempotency-Key`. Creation
writes equal-and-opposite source/destination postings and bumps both account
`balance_version` values. Members may list and read current POSTED transfers.
The creator or an admin may void a POSTED transfer with `If-Match` and an
`Idempotency-Key`. Transfer has no replace route.

An admin may reconcile an ACTIVE account under
`/api/v1/households/{householdId}/accounts/{accountId}/reconciliations` with an
`Idempotency-Key`. The server assigns household-local today, checks
`expectedBalanceVersion`, writes a signed ADJUSTMENT for the delta with immutable
balance snapshots, and bumps `balance_version`. Admins may list and read
adjustments under `/api/v1/households/{householdId}/adjustments` and void a
POSTED adjustment with `If-Match` and an `Idempotency-Key`.

Active members may read
`/api/v1/households/{householdId}/accounts/{accountId}/ledger` with optional
`from`/`to`/`page`/`size`. The response is one snapshot with period opening and
closing balances plus paged APPLY/REVERSE postings and running balances.
Adjustment amount and date are visible; reconciliation reason text and
actual-balance snapshots are not.

Active members may call
`GET /api/v1/households/{householdId}/reports/dashboard`. The snapshot returns
period totals (`expense`/`income`/`net`), account balances, recent
expense/income/transfer activity, spending pace, category breakdown/changes,
beneficiary spending, `balanceCoverage`, and per-period `coverage` metadata.
`elapsedDayAverage` is present when the current period is fully covered;
comparison difference/percent/direction are null when either period has an
opening gap. Transfers and adjustments do not inflate income or expense.

Active members may call
`GET /api/v1/households/{householdId}/reports/analytics` for overview totals,
six-month expense trend (partial current month), category breakdown/changes,
and the same coverage / elapsed-day average rules as the dashboard. Analytics
also returns structured `insight` (`kind`, optional `metric`, `contributors`)
for localized frontend text when comparison coverage is complete; otherwise
`insight` is null. Entry-options and filtered list summaries are not included
yet.

Recover an existing active admin without HTTP by supplying a runtime password:

```bash
./mvnw spring-boot:run -Dspring-boot.run.arguments="\
--expense.recovery.enabled=true \
--expense.recovery.admin-email=admin@example.com \
--expense.recovery.admin-password=$RECOVERY_ADMIN_PASSWORD"
```

## Command shortcuts

Run `make` or `make help` to list available shortcuts. Common commands:

```bash
make compile                         # Fast compile feedback
make test                            # Unit tests only
make test-focused TEST=ProfileIT     # Selected integration tests (requires Docker)
make check                           # Full Maven verification, then container smoke tests
make up                              # Build and start local containers
make logs                            # Follow container logs
make health                          # Check API readiness and liveness
make down                            # Stop containers, retaining database data
```

`make build` packages the application without running tests. `make run` starts it
outside Compose and requires a separately provisioned database, as described below.

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

### Fast local feedback

For a source edit, compile without packaging or starting Docker:

```bash
./mvnw --no-transfer-progress compile
```

With Java 21 and Docker running, run only the relevant integration class or method:

```bash
sh scripts/test-focused.sh ProfileIT
sh scripts/test-focused.sh 'ProfileIT#readsAndUpdatesDisplayName'
sh scripts/test-focused.sh 'ProfileIT,AuthSessionIT'
```

The runner compiles current application and test sources, then invokes Failsafe and
checks its result without packaging a JAR. An unmatched selector fails. It runs only
the selected integration tests, not unit tests or the complete verification gate.
Reports remain in `target/failsafe-reports`. Run the full commands below before
merging; focused tests do not replace them.

Avoid `clean` during ordinary edits: retaining `target` allows Maven to reuse build
outputs where supported. Use `clean` when diagnosing stale outputs or when a fresh
build is required. `./mvnw test` runs unit tests only; the PostgreSQL integration
classes use the `IT` suffix and require Failsafe.

Docker builds require BuildKit (standard with current Compose v2). Rebuild with
`docker compose up --build -d --wait` after source changes. The build caches the
Maven repository, including the wrapper distribution, across source changes on the
same builder. The first build still downloads dependencies; a new or pruned builder
has a cold cache. BuildKit cache contents are not copied into the runtime image.

### Full verification

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
