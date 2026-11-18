# ADR-0003: PESSIMISTIC stays the default locking strategy

Date: 2026-11-18
Status: Accepted

## Context

Allocation can keep concurrent requests for the same item from overselling in three ways, chosen with
`allocation.locking-mode`. We needed one default. The numbers below come from
[docs/benchmarks.md](../benchmarks.md): one laptop (6 logical cores, Postgres 18 in Docker), 30 s runs,
median of 3 repeats, 10 to 200 concurrent users, stock large enough that it never runs out. "Hot" means
every user orders the same item; "spread" means 100 items picked at random.

## Options considered

| | Hot item (successful allocations/s) | Hot item, p99 at 200 VUs | Spread (req/s) | Spread, p99 at 200 VUs |
|---|---|---|---|---|
| **PESSIMISTIC**: `SELECT ... FOR UPDATE` | 94-102, 0% errors | 2.7 s | 438-471 | 0.8 s |
| **OPTIMISTIC**: `@Version` + up to 5 retries | 63-70, **14-49% of requests end as 503** (3.4k-10.7k retries per run) | 2.8 s | 295-412 | 1.2 s |
| **ATOMIC_UPDATE**: one conditional `UPDATE` | 88-96, 0% errors | 3.2 s | 272-399 | 1.2 s |

No mode oversold in any of the 72 runs.

## Decision

Keep **PESSIMISTIC** as the default `allocation.locking-mode`.

Like `ATOMIC_UPDATE` it had no errors, so a caller never has to handle a 503 under contention, and it had
the highest median successful throughput in both scenarios at every VU count. The advantage over
`ATOMIC_UPDATE` is small on the hot item (the ranges overlap at 10, 50 and 200 VUs) but consistent on spread.

## Consequences

- Requests for one item queue on its row lock: on the hot item throughput stayed near 100/s and p50
  roughly doubled each time users doubled from 50 to 200. That is the cost of the default.
- `OPTIMISTIC` is the wrong choice for a hot item with these settings; if it is used, clients must handle
  `503` + `Retry-After`. These numbers gave no throughput reason to use it, even on spread.
- `ATOMIC_UPDATE` was not faster than `PESSIMISTIC` here. Re-measure before switching (other hardware,
  connection pool size, a transaction that does more than the decrement). That reasoning is mine, not measured.
- The default may change if the hardware or pool size does: the results are from one machine and
  some ranges overlap.

## 30-second version

"I built three ways to stop two orders buying the same last item: a row lock, optimistic versioning with
retries, and a single conditional UPDATE. I load-tested all three with k6, on one hot item and on a hundred
items, up to 200 users. Row locking and the atomic update both held about 90 to 100 orders a second on the
hot item with zero errors, while optimistic locking failed between 14 and 49 percent of requests after five
retries. On spread-out items, row locking was fastest. I expected the atomic update to win and it didn't,
so I kept pessimistic locking as the default and left the other two behind a config flag. The caveat is that
it's one laptop with three repeats, so I'd re-run it on production-like hardware before trusting the margins."
