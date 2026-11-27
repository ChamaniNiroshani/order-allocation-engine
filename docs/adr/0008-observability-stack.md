# ADR-0008: Micrometer and OpenTelemetry, with Prometheus, Grafana and Jaeger

Date: 2026-11-20 (written 2026-11-27 from the code and commit history)
Status: Accepted

## Context

Infrastructure metrics cannot say whether allocation works, and a metric that says something is slow does not say
where. We need business metrics, per-request traces that show lock waits, and logs that can be tied to a request. It
has to run on a laptop from this repository.

## Options

1. **Logs only.** Nothing extra to run, but no latency percentiles, alert rules or view of where time goes.
2. **A hosted monitoring product.** Little to operate, but it needs an account and keys, and a fresh checkout would
   not have working dashboards.
3. **Open-source stack in Docker Compose:** Micrometer metrics to Prometheus and Grafana, and Micrometer
   observations exported through OpenTelemetry (OTLP) to Jaeger.

## Decision

Use option 3.

- **Metrics:** business meters are defined in `AllocationMetrics`, with low-cardinality tags only.
- **Dashboard and alerts:** Grafana is provisioned from the repo; [SLOs](../slo.md) have two Prometheus alert rules.
- **Traces:** HTTP, saga steps, gateway calls and JDBC (connections and queries, without parameter values).
- **Logs:** readable text locally, ECS JSON in the `json` and `prod` profiles, with request, trace and order ids.

## Consequences

- A fresh checkout gets dashboards and traces with `docker compose up -d`; image versions are pinned.
- The application code uses Micrometer APIs and exports traces over OTLP, so another backend can be used.
- A lock wait shows up as one long `SELECT ... FOR UPDATE` span.
- These are more containers to run, on the same laptop that runs the load tests.
- There is no Alertmanager: alerts are visible in Prometheus but notify nobody. Jaeger keeps traces in memory.
- Trace export is off in the `prod` profile because no tracing backend is set up there, and in the tests.
