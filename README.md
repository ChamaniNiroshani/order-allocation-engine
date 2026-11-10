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

## Run the tests

```bash
./mvnw verify
```

Tests use the same Postgres instance via Spring Boot's Docker Compose
support, so Docker must be running.

## Configuration

Database connection settings are read from environment variables (see
`.env.example`), with sane local defaults baked into `compose.yaml` and
`application.properties`. `.env` is gitignored — never commit real
credentials.
