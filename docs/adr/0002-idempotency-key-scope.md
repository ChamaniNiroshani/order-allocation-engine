# ADR-0002: Idempotency key scoped to key + payload hash, 422 on mismatch

Date: 2026-11-10
Status: Accepted

## Context

Clients retry requests — on timeouts, network blips, or at-least-once
delivery from an upstream system. The engine must make retries safe
(US2, NFR2), but an idempotency key alone doesn't say what should happen
if the *same key* is reused with a *different* request body. That can
happen by accident (a client bug reusing a key) or by a bad actor probing
the API, and silently returning the old order either way would hide a
real bug or let state be queried under someone else's key.

## Options considered

1. **Key only, no payload check** — simplest, but a reused key with a
   different `itemId`/`quantityRequested` silently returns the original
   order's result as if it matched the new request. A client bug (key
   reuse across logically different orders) would never surface.
2. **Key + payload hash, silently create a new order on mismatch** —
   defeats the purpose of idempotency keys (uniqueness is enforced on the
   key at the database level, so this would just throw a constraint
   violation instead of a clean error).
3. **Key + payload hash, reject on mismatch** — detect the mismatch and
   return a clear error so the caller knows their key reuse is a bug,
   rather than silently serving the wrong thing.

## Decision

Each `Order` stores a `requestHash` (SHA-256 of `itemId` +
`quantityRequested`) alongside its `idempotencyKey`. On a retry:

- Same key, same hash → return the stored order (the normal idempotent
  retry case).
- Same key, different hash → reject with **`422 Unprocessable Entity`**
  (`IdempotencyKeyReusedException`), rather than `409` (which is reserved
  for the insufficient-inventory business outcome, ADR-0001) or a generic
  `400`.

The idempotency lookup happens *before* any stock is locked or changed. It
does not by itself stop a race: two concurrent first requests with the same
key can both find nothing. The unique constraint on `idempotency_key`
settles that. Only one order can commit. The other request's transaction
rolls back, together with any stock change it made, and the caller
(`OrderAllocationFacade`, outside that transaction) reads the stored order,
checks its hash, and returns it. `IdempotencyRaceTest` and
`AbstractAllocationConcurrencyTest` force both requests past the lookup
before either commits.

`POST /api/checkout` and `POST /api/fulfilments` follow the same rule. In
checkout the order is inserted before stock is reserved, so the duplicate
fails before it reserves or charges anything; the request that lost may be
answered `202` while the other is still in progress. The fulfilment hash
covers its own request fields.

> **Updated 2026-11-27.** When this record was written, the code took the row
> lock (`SELECT ... FOR UPDATE`) on the inventory item first and looked the
> key up after it. That changed when the locking strategy became switchable
> ([#15](https://github.com/ChamaniNiroshani/order-allocation-engine/pull/15)):
> `OPTIMISTIC` and `ATOMIC_UPDATE` take no lock before the lookup.

Rows created before the `requestHash` column existed have no hash
(`null`), and `matchesRequestHash` treats `null` as a match, so older
rows aren't retroactively rejected.

## Consequences

- A client that reuses a key by mistake gets an explicit, actionable
  error instead of a confusing or silently wrong order.
- `422` is distinct from `409` in the API, so callers can tell "your
  request itself is invalid/inconsistent" apart from "the request was
  valid but inventory ran out."
- Adds one column and one hash computation per request — negligible cost
  for the correctness it buys.
- The hash only covers `itemId` and `quantityRequested` today; if more
  fields are added to the order request later, the hash computation needs
  to be updated to include them, or it stops catching genuine mismatches.
