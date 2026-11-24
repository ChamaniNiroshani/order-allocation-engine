# ADR-0005: Batch rows are locked in ascending id order

Date: 2026-11-24
Status: Accepted

## Context

A fulfilment order locks the batches of its SKU (`SELECT ... WHERE sku = :sku ORDER BY id FOR UPDATE`, see
`InventoryBatchRepository.lockBySkuOrderById`) and then plans and decrements under those locks. Strategies
prefer different warehouses: an order delivered near warehouse A wants A's batch first, one delivered near B
wants B's first. If each transaction locked rows in its own preference order, two concurrent orders could end up
holding one lock each and waiting for the other's.

## Options considered

1. **Lock in preference order** (the order the strategy would use them). Natural, but opposite preferences give
   opposite lock orders.
2. **Lock in one global order** (ascending batch id), independent of any preference. Every transaction
   acquires locks in the same order, so a cycle of waits cannot form.
3. **Let Postgres detect deadlocks and retry the victim.** Postgres does detect them, but only after
   `deadlock_timeout` (1 s by default), it aborts one transaction, and the caller needs retry logic.

## Decision

Lock every batch of the SKU in ascending id order (option 2) before planning.

## Consequences

- What happens without it: this was tried in a throwaway test (not committed). Two transactions locked two
  batches in opposite order, each waiting for a latch until both held their first lock. Postgres aborted one of
  them with `ERROR: deadlock detected` (SQLSTATE 40P01), through Spring as `PessimisticLockingFailureException`;
  the other committed. In the API that would be a 500 for a valid request.
- `SplitDeadlockTest` runs 50 rounds of four concurrent orders (two kinds with opposite warehouse
  preferences, all needing stock from both warehouses) and asserts no exception, exactly one order fits, and the
  stock that left the shelves equals what the lines say. It exercises the ordered locking through the real
  service; it was not run against a variant without the ordering, so the proof that the ordering is what protects
  it is the throwaway test above, not this one.
- All batches of the SKU are locked, including the ones a plan does not use. Orders for the same SKU are
  serialized; orders for different SKUs are not.
- Any new code that locks several batches (a cancellation, a restock) must also lock in ascending id order.
