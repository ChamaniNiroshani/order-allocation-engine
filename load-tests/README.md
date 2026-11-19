# Load tests (k6)

Two scenarios against `POST /api/orders`:

| Script | What it does |
|---|---|
| `hot-item.js` | `setup()` creates ONE item; every VU orders it. Worst-case contention. |
| `spread.js` | `setup()` creates `ITEMS` items; each request picks one at random. Low contention. |

Both send a unique `Idempotency-Key` per request. `409` (insufficient stock) counts as a correct
outcome, so `http_req_failed` only counts real errors (5xx including `503` contention, timeouts).
Custom counters: `orders_allocated` (201), `orders_rejected` (409), `orders_contention` (503).

## Run one script (PowerShell, k6 in Docker)

1. Start the app (`.\mvnw.cmd spring-boot:run`, Docker Desktop running).
2. From the repo root:

```powershell
New-Item -ItemType Directory -Force load-tests/results | Out-Null
docker run --rm -i -v "${PWD}/load-tests:/scripts" `
  -e VUS=50 -e DURATION=30s -e MODE=PESSIMISTIC `
  grafana/k6 run /scripts/hot-item.js
```

`spread.js` works the same way. The `jslib.k6.io` summary helper is fetched on start, so k6 needs
internet access.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://host.docker.internal:8080` | App URL as seen from the k6 container |
| `VUS` | `50` | Virtual users |
| `DURATION` | `30s` | Test length |
| `STOCK` | `1000` | Initial stock per item |
| `QTY` | `1` | Quantity per order |
| `ITEMS` | `100` | Number of items (`spread.js` only) |
| `MODE` | `unknown` | Locking mode, used in the result file name only. Set the real mode on the app with `--allocation.locking-mode=...` |
| `RESULT_DIR` | `results` | Output folder under `load-tests/` (must exist) |
| `SAVE_RESULT` | `true` | `false` skips the JSON file (used for warm-ups) |

With the default `STOCK=1000` a hot item runs out after 1000 orders and the rest of the run measures
the `409` rejection path. Use a large `STOCK` (for example `-e STOCK=100000000`) to benchmark allocation.

## Results

`handleSummary()` writes `load-tests/results/<script>-<mode>-<vus>vus-<timestamp>.json` and prints the
normal text summary. `load-tests/results/` is git-ignored: raw results are never committed.

## Smoke test (both flows, for metrics and dashboards)

`smoke.js` sends half its requests to `POST /api/orders` and half to `POST /api/checkout`, over 20 small items.
It has no pass/fail thresholds and is not a benchmark: it puts traffic on `/actuator/prometheus` and the dashboards.
Start the app with some payment failures to see declines (402), timeouts (502) and compensations:

```powershell
java -jar target\order-allocation-engine-0.0.1-SNAPSHOT.jar --payment.fake.failure-rate=0.2 --payment.fake.timeout-rate=0.05
docker run --rm -i -v "${PWD}/load-tests:/scripts" -e VUS=10 -e DURATION=20s grafana/k6 run --quiet /scripts/smoke.js
```
