# ADR-0004: Multi-warehouse fulfilment is a separate flow, not a migration of `inventory_item`

Date: 2026-11-24
Status: Accepted

## Context

Allocation strategies (nearest warehouse, first-expiry-first-out, split shipments) need stock per **batch**
(SKU, warehouse, expiry date). The existing flows work on `inventory_item`, one row per item with a single
quantity, and that model has no warehouse and no expiry.

Those flows are the ones with evidence behind them: `POST /api/orders` has three switchable locking modes
benchmarked in [docs/benchmarks.md](../benchmarks.md) (ADR-0003), and `POST /api/checkout` (saga, expiry job,
refund on expiry) has been chaos-tested ([docs/chaos.md](../chaos.md)). Changing their tables would invalidate
those numbers and those experiments.

## Options considered

1. **Migrate `inventory_item` to batches.** One model, no duplication. But every query, entity, test, benchmark
   and the saga change at once, the benchmarks and chaos results no longer describe the code, and one mistake
   breaks the working flows. A big-bang rewrite.
2. **A separate flow next to the old one.** New tables (`warehouse`, `inventory_batch`, `fulfilment_order`,
   `fulfilment_line`) and a new endpoint family (`/api/fulfilments`). The old flows are untouched. Cost: two
   inventory models for a while, and stock held in one is invisible to the other.
3. **Add warehouse and expiry columns to `inventory_item`.** Smaller change, but one row per item cannot hold
   several batches, so it cannot express the thing the strategies choose between.

## Decision

Build the warehouse and batch model as a **new, separate flow** (option 2). `inventory_item`, `orders`, the
checkout saga, the benchmarks and the chaos tests are not changed.

## Consequences

- The benchmarked and chaos-tested flows stay stable, and the new flow can be reviewed, tested and discarded on
  its own.
- Two inventory models exist side by side: the same product could be stocked in both and the two never reconcile.
  This is acceptable while the new flow is a demonstrator; it would not be acceptable as a permanent state.
- In production the old flow would be moved over gradually (the strangler pattern): route a slice of traffic to
  the new flow, compare, move more, and retire `inventory_item` last. That migration is not part of this work.
- The new flow does not use the saga (no payment step, no reservation expiry): `qty_reserved` exists in the
  schema but nothing sets it yet.
- Demo data (Colombo, Kandy, Galle) is a repeatable Flyway migration in `db/seed`, loaded only by the `dev`
  profile, so it can never reach a real database.
