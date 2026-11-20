# Order Allocation & Transaction Engine

Spring Boot backend that allocates orders against limited inventory under
concurrency-safe rules, with idempotent retries and an append-only
transaction/audit trail.

## Prerequisites

- JDK 21
- Docker Desktop (or another Docker engine) running locally

## Run it locally

1. Copy the environment file and adjust if needed:
   ```bash
   cp .env.example .env
   ```
2. Start the database and the app together:
   ```bash
   docker compose up -d
   ./mvnw spring-boot:run
   ```
   Spring Boot's Docker Compose support also starts Postgres automatically
   on `./mvnw spring-boot:run` if it isn't already running — the explicit
   `docker compose up -d` above is mainly useful for keeping the DB up
   between app restarts, or for running migrations/tests against it
   directly.
3. The API is available at `http://localhost:8080`.

## Logs

Locally the logs are readable text; every line carries `req=<request id>` (the `X-Request-Id` header, or a generated
one, returned in the response), `trace=<trace id>` (the same id Jaeger shows) and, inside an order, `order=<order id>`. SQL is not logged by default.

| Profile | Effect |
|---|---|
| `dev` | print every SQL statement (`--spring.profiles.active=dev`) |
| `json` or `prod` | one JSON object per line (Elastic Common Schema), with `requestId`, `orderId`, `traceId`, `spanId` and the business event fields |

## Metrics, dashboards and traces

`docker compose up -d` also starts Prometheus, Grafana and Jaeger (images pinned in `compose.yaml`).
With the app running on the host:

| What | URL |
|---|---|
| Grafana dashboard "Allocation Engine" (no login to view) | http://localhost:3000/d/allocation-engine/allocation-engine |
| Prometheus (targets: Status > Targets) | http://localhost:9090 |
| Raw metrics the app exposes | http://localhost:8080/actuator/prometheus |
| Jaeger, traces (service `order-allocation-engine`) | http://localhost:16686 |

Every request is traced (HTTP, then each saga step, the payment gateway call and the SQL under it) and sent to Jaeger
over OTLP, sampling 100% locally (`management.tracing.sampling.probability`). Without Jaeger running the app still works,
but the exporter logs an ERROR ("Failed to export spans") each time it tries to send a batch. Tracing is off in the tests and in `load-tests/run-matrix.ps1`.

`load-tests/smoke.js` puts traffic on both flows (see `load-tests/README.md`). The Grafana admin password is
`GRAFANA_ADMIN_PASSWORD` (default `admin`, for local use only).

## Run the tests

```bash
./mvnw verify
```

Tests start their own throwaway Postgres with Testcontainers, so Docker must
be running. No local DB or env vars are needed.

## Configuration

Database connection settings are read from environment variables (see
`.env.example`), with sane local defaults baked into `compose.yaml` and
`application.properties`. `.env` is gitignored — never commit real
credentials.
