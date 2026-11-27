# Architecture decision records

New records start from [the template](0000-template.md).

| # | Title | Decision in one line |
|---|---|---|
| [0001](0001-partial-allocation-policy.md) | All-or-nothing allocation policy | An order gets the full quantity or nothing; a rejection is stored as `FAILED`. |
| [0002](0002-idempotency-key-scope.md) | Idempotency key scope | The key is bound to a hash of the request; reusing it with a different request is a 422. |
| [0003](0003-default-locking-strategy.md) | Default locking strategy | `PESSIMISTIC` is the default; the other two modes stay behind a setting. |
| [0004](0004-separate-multi-warehouse-fulfilment-flow.md) | Separate fulfilment flow | Warehouse and batch stock is a new flow, not a migration of `inventory_item`. |
| [0005](0005-lock-ordering.md) | Lock ordering | Batch rows are locked in ascending id order, so these locks cannot form a deadlock cycle. |
| [0006](0006-orchestrated-checkout-saga.md) | Orchestrated checkout saga | One orchestrator runs short transactions, with no lock held during payment, and compensates on failure. |
| [0007](0007-flyway-owns-the-schema.md) | Flyway owns the schema | Versioned SQL migrations change the schema; Hibernate only validates it. |
| [0008](0008-observability-stack.md) | Observability stack | Micrometer and OpenTelemetry feed Prometheus, Grafana and Jaeger. |
| [0009](0009-allocation-strategy-pattern.md) | Allocation strategy pattern | `NEAREST`, `FEFO` and `SPLIT` are pure functions behind one interface. |
| [0010](0010-no-free-tier-hosting.md) | No free-tier hosting | The demo is a recorded video; the project stays deployment-ready but is not hosted. |
