# Running and operating it

The one-command setup is in the [README](../README.md#run-it-locally). This page has the rest.

## Run from the IDE or Maven

Needs JDK 21 and Docker.

1. Copy the environment file and adjust if needed (optional: the defaults work):
   ```bash
   cp .env.example .env
   ```
2. Start the app:
   ```bash
   ./mvnw spring-boot:run
   ```
   Spring Boot's Docker Compose support starts Postgres and the observability services. Run `docker compose up -d`
   first to keep them up between app restarts.
3. The API is on `http://localhost:8080`, with Swagger UI at `http://localhost:8080/swagger-ui.html`.

## Run everything in Docker (app included)

```bash
docker compose --profile app up -d --build
```

Builds the image from the [`Dockerfile`](../Dockerfile) (multi-stage, layered, non-root, JRE 21, class data sharing) and
starts Postgres, the app, Prometheus, Grafana and Jaeger. The app is on **http://localhost:8081** (8080 stays free for
runs from the IDE) and `/actuator/health/readiness` is its healthcheck. The `app` service is in a compose profile so
that running the app from the IDE does not start a second copy.

### The app container keeps restarting

If its log says `Detected applied migration not resolved locally: dev seed warehouses`, the local database volume was
used with the `dev` profile, which adds demo data as a migration. The app in Docker does not load that migration, so
Flyway rejects the database. To start again with an empty database, which **deletes all local data** (database,
Prometheus and Grafana volumes):

```bash
docker compose --profile app down -v
docker compose --profile app up -d --build
```

## Logs

Locally the logs are readable text; every line carries `req=<request id>` (the `X-Request-Id` header, or a generated
one, returned in the response), `trace=<trace id>` (the same id Jaeger shows) and, inside an order, `order=<order id>`.
SQL is not logged by default.

| Profile | Effect |
|---|---|
| `dev` | print every SQL statement and load the demo warehouses and batches (`--spring.profiles.active=dev`) |
| `json` or `prod` | one JSON object per line (Elastic Common Schema), with `requestId`, `orderId`, `traceId`, `spanId` and the business event fields |

What else `prod` changes: [deploy.md](deploy.md#what-the-prod-profile-changes).

## Metrics, dashboards and traces

`docker compose up -d` also starts Prometheus, Grafana and Jaeger (images pinned in `compose.yaml`).

| What | URL |
|---|---|
| Grafana dashboard "Allocation Engine" (no login to view) | http://localhost:3000/d/allocation-engine/allocation-engine |
| Prometheus (targets: Status > Targets) | http://localhost:9090 |
| Raw metrics the app exposes | `/actuator/prometheus` on the app's port (8080 or 8081) |
| Jaeger, traces (service `order-allocation-engine`) | http://localhost:16686 |

Every request is traced (HTTP, then each saga step, the payment gateway call and the SQL under it) and sent to Jaeger
over OTLP, sampling 100% locally (`management.tracing.sampling.probability`). Without Jaeger running the app still works,
but the exporter logs an ERROR ("Failed to export spans") each time it tries to send a batch. Tracing is off in the
tests and in `load-tests/run-matrix.ps1`.

The Grafana admin password is `GRAFANA_ADMIN_PASSWORD` (default `admin`, for local use only).

## Try the fulfilment strategies

With the app running from Maven with the `dev` profile, [`demo-fulfilment.ps1`](demo-fulfilment.ps1) sends the same
order with `NEAREST`, `FEFO` and `SPLIT` and prints which batches each one used.

## Tests and load tests

```bash
./mvnw verify
```

Tests start their own throwaway Postgres with Testcontainers, so Docker must be running. No local database or
environment variables are needed. Load tests: [`load-tests/README.md`](../load-tests/README.md).

## Configuration

Database connection settings are read from environment variables (see `.env.example`), with local defaults in
`compose.yaml` and `application.properties`. `.env` is gitignored: never commit real credentials.
