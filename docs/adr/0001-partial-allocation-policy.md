# ADR-0001: All-or-nothing allocation policy

Date: 2026-11-10
Status: Accepted

## Context

When an order requests more units than are currently available, the
system needs one documented policy, applied consistently (FR4). Two
reasonable options exist:

- **All-or-nothing**: the order either gets the full quantity requested,
  or nothing at all.
- **Partial fill**: the order gets whatever is available, and the
  remainder is either dropped or tracked as a backorder.

## Options considered

1. **All-or-nothing** — simple to reason about and to test. A client
   always knows exactly what it got: the full amount, or nothing. No need
   for a separate backorder/remainder concept.
2. **Partial fill, remainder dropped** — the client gets a confusing
   "sort of succeeded" response and silently loses the rest of the order.
3. **Partial fill, remainder backordered** — correct in spirit, but adds a
   second order lifecycle (the backordered remainder needs its own status,
   its own retry/cancellation rules, and its own audit trail) for a
   one-month solo project where the differentiator is concurrency
   correctness, not order-fulfillment breadth.

## Decision

Orders are allocated **all-or-nothing**: if `quantityRequested` exceeds
`quantityAvailable` at allocation time, the order is saved with
`status = FAILED` and a `failureReason` describing the shortfall, and no
inventory is decremented.

## Consequences

- The allocation logic and its tests stay simple: there is exactly one
  success shape (fully allocated) and one failure shape (failed with a
  reason), with no partial-quantity edge cases to reason about under
  concurrency.
- A `FAILED` order is still saved and returned (not thrown as an
  exception), so the rejection is recorded in the audit trail and an
  idempotent retry returns the same result.
- Clients that want partial fulfillment have to split the order into
  smaller requests themselves — the engine does not do this for them.
  This is an explicit scope cut, not an oversight (see `PROPOSAL.md`,
  out of scope).
