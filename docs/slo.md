# Service level objectives

Two promises about the allocation endpoints (`POST /api/orders` and `POST /api/checkout`). The targets are for this
demo, not measurements: they say what "working" means, so the dashboard and the alerts have something to compare to.

## What is measured (SLIs)

| SLI | Metric | Meaning |
|---|---|---|
| Latency | `allocation_latency_seconds` (histogram, one sample per request, whatever the outcome) | how long a request takes |
| Availability | `http_server_requests_seconds_count` on `/api/*` | the share of requests that are not a server error |

## Objectives

**SLO-1, latency:** 99% of allocation requests complete in under 200 ms (p99 < 200 ms), over a rolling 1 h window.
The 200 ms bucket exists exactly (`le="0.2"`), so compliance is a ratio, not an estimate:

```promql
sum(rate(allocation_latency_seconds_bucket{le="0.2"}[1h])) / sum(rate(allocation_latency_seconds_count[1h]))   # want >= 0.99
```

**SLO-2, availability:** 99.9% of requests are not 5xx.

**What counts as an error.** Only 5xx: `502` (payment timed out), `503` (optimistic-lock retries exhausted) and
unexpected `500`s. These are **correct outcomes, not errors**: `409` (no stock; the engine did its job) and `402`
(payment declined). `4xx` caused by the caller (`400`, `404`, `422`) are not errors either. Only routed `/api/*`
endpoints are counted, not the actuator.

## Error budget

A 99.9% objective leaves a budget of 0.1% of requests. That is **10 failed requests per 10,000**. While the budget
is unspent a failure is expected and fine; once it is spent, reliability work takes priority over new features.

## Alerts

[`observability/prometheus/alerts.yml`](../observability/prometheus/alerts.yml), evaluated by the Prometheus in
`compose.yaml` (there is no Alertmanager: they appear on http://localhost:9090/alerts).

| Alert | Fires when |
|---|---|
| `AllocationLatencyHigh` | p99 latency > 200 ms for 5 minutes |
| `AllocationErrorRateHigh` | 5xx ratio > 0.1% for 5 minutes |

Both SLO limits are also drawn as red threshold lines on the latency and error-rate panels of the Grafana dashboard.

## In production

A fixed threshold with `for: 5m` is a demo shortcut: it is slow to notice a severe outage and noisy for a brief
blip. In production I would alert on the **burn rate** of the error budget over **multiple windows**: page when
the budget burns fast over both a long and a short window (for example 14.4x over 1 h and 5 min, which would spend
2% of a 30-day budget in an hour), open a ticket for a slow burn (for example 1x over 3 days), and add the
short window so the alert clears as soon as the problem does. That ties paging to user impact and budget, not to a
number someone once picked.
