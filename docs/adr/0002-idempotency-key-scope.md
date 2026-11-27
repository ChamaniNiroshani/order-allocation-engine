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

A key also belongs to **one flow**: `POST /api/orders`, `POST /api/checkout`
or `POST /api/fulfilments`. Sent to another flow it is rejected with `422`,
whatever the payload; that flow's record is not returned, and nothing is
created or changed. The table `idempotency_key` records each key's flow, and a
request inserts its row in the same transaction as the record it creates, so a
request that saved nothing does not use up its key.

The idempotency lookup happens *before* any stock is locked or changed. It
does not by itself stop a race: two concurrent first requests with the same
key can both find nothing. The primary key of `idempotency_key` settles that,
across flows too: only one request can commit the key. The other request's
transaction rolls back, together with any stock change it made, and the caller
(`OrderAllocationFacade`, outside that transaction) reads the stored order,
checks its hash, and returns it. `IdempotencyRaceTest` and
`AbstractAllocationConcurrencyTest` force both requests past the lookup
before either commits.

`POST /api/checkout` and `POST /api/fulfilments` follow the same rule. In
checkout the key is claimed and the order inserted before stock is reserved,
so the duplicate fails before it reserves or charges anything; the request
that lost may be answered `202` while the other is still in progress. The
fulfilment hash covers its own request fields. If the two racing requests are
on different flows, the one that lost gets `422` (`IdempotencyScopeTest`).

> **Updated 2026-11-27.** When this record was written, the code took the row
> lock (`SELECT ... FOR UPDATE`) on the inventory item first and looked the
> key up after it. That changed when the locking strategy became switchable
> ([#15](https://github.com/ChamaniNiroshani/order-allocation-engine/pull/15)):
> `OPTIMISTIC` and `ATOMIC_UPDATE` take no lock before the lookup.

> **Updated 2026-11-27: keys are scoped per flow.** Before this, direct orders
> and checkouts looked keys up in the same table, and their hashes cover the
> same two fields. A direct order's key sent to `/api/checkout` with the same
> item and quantity was answered `201` with that order, and nothing was
> charged. When the two arrived at the same time, one of them got a `500`. A
> fulfilment, stored in its own table, could reuse an order's key.
> Migration `V6` adds `idempotency_key` and fills it from the stored rows: an
> order with `reserved_at`, or rejected with checkout's reason, counts as a
> checkout, any other as a direct order. A key that both an order and a
> fulfilment had stored stays with the order, so that fulfilment can no longer
> be replayed with it.

Rows created before the `requestHash` column existed have no hash
(`null`), and `matchesRequestHash` treats `null` as a match, so older
rows aren't retroactively rejected.

## Consequences

- A client that reuses a key by mistake gets an explicit, actionable
  error instead of a confusing or silently wrong order.
- `422` is distinct from `409` in the API, so callers can tell "your
  request itself is invalid/inconsistent" apart from "the request was
  valid but inventory ran out."
- Clients need a new key for each operation, not only for each payload.
- The flow rule costs one more table, a lookup per request and an insert per
  new record.
- Adds one column and one hash computation per request — negligible cost
  for the correctness it buys.
- The hash only covers `itemId` and `quantityRequested` today; if more
  fields are added to the order request later, the hash computation needs
  to be updated to include them, or it stops catching genuine mismatches.
