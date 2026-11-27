# ADR-0009: Fulfilment allocation rules are interchangeable strategies

Date: 2026-11-24 (written 2026-11-27 from the code and commit history)
Status: Accepted

## Context

In the fulfilment flow ([ADR-0004](0004-separate-multi-warehouse-fulfilment-flow.md)), which batches serve an order
is a policy that varies: nearest warehouse, first-expiry-first-out, or split shipment. Locking and saving the result
do not vary.

## Options

1. **A `switch` in the service.** Little code at first, but the rules, locking and persistence mix, and testing a
   rule needs the service and a database.
2. **Strategies that query and update the database themselves.** Each could load only what it needs, but each would
   also have to get locking and transactions right.
3. **Strategies as pure functions** behind one interface: request and candidate batches in, plan out.

## Decision

Use option 3. `AllocationStrategy` implementations (`NEAREST`, `FEFO`, `SPLIT`) use no database or Spring services
to plan. `FulfilmentService` locks the batches ([ADR-0005](0005-lock-ordering.md)), calls the strategy and applies
the plan in one transaction.

- A shared base class applies the shelf-life filter before any strategy runs, in a `final` method.
- Plans are all-or-nothing ([ADR-0001](0001-partial-allocation-policy.md)).
- The request may name a strategy; otherwise the customer tier's configured default applies.
- The application fails to start if a strategy type has no implementation or two, or a tier has no default.
- The chosen strategy is stored on the order, returned, and used as a metric tag.

## Consequences

- Each rule is unit-tested with plain objects, and locking exists in one place.
- Adding a strategy takes a new enum value, a class, and a migration, because a database check constraint lists the
  allowed values.
- Strategies receive candidates instead of querying, so the service locks every batch of the SKU, including ones the
  plan does not use. Orders for the same SKU are therefore serialized.
