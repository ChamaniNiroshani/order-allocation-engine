# Requirements

## 1. User Stories

| ID | Story |
|----|-------|
| US1 | As a client system, I want to submit an order for a specific item and quantity so that it can be allocated against available inventory. |
| US2 | As a client system, I want to submit an order with an idempotency key so that a retried request never causes a duplicate allocation. |
| US3 | As a client system, I want a clear failure response when there isn't enough inventory, so I can decide whether to retry, split, or cancel. |
| US4 | As an operator, I want every allocation, failure, and rollback recorded in an audit trail, so I can reconstruct what happened to any order after the fact. |
| US5 | As an operator, I want inventory to never be over-allocated, even when many orders arrive for the same item at once. |
| US6 | As a developer, I want a documented comparison of locking strategies, so the concurrency-control choice is justified rather than arbitrary. |

## 2. Functional Requirements

| ID | Requirement |
|----|-------------|
| FR1 | The system shall accept an order (item ID, quantity, idempotency key) via a REST endpoint. |
| FR2 | The system shall reject an order with `quantity <= 0` or an unknown item ID. |
| FR3 | The system shall allocate an order in full when requested quantity ≤ available quantity. |
| FR4 | The system shall apply a single, documented policy (all-or-nothing or partial fill) when requested quantity > available quantity. |
| FR5 | The system shall reject an order outright when available quantity is 0. |
| FR6 | The system shall detect a duplicate idempotency key and return the original result without re-allocating. |
| FR7 | The system shall re-check available quantity inside the same database transaction that performs the decrement (no read-then-write across separate transactions). |
| FR8 | The system shall write a transaction log entry for every allocation, failure, and rollback, in the same transaction as the state change it describes. |
| FR9 | The system shall support compensating (rolling back) a partially completed allocation, logging the compensation as its own entry. |
| FR10 | The system shall allow the full history of any order to be reconstructed from the transaction log alone. |
| FR11 | The system shall expose an inventory-status endpoint reflecting current available quantity per item. |

## 3. Non-Functional Requirements

| ID | Requirement |
|----|-------------|
| NFR1 | **Consistency**: under concurrent requests for the same item, total allocated quantity shall never exceed available quantity. |
| NFR2 | **Idempotency**: retrying an identical request (same idempotency key) shall be safe to do any number of times. |
| NFR3 | **Auditability**: the transaction log shall be append-only; no update or delete operations against it. |
| NFR4 | **Recoverability**: a bounded number of retries (e.g. 3) shall be attempted on an optimistic-lock conflict before failing the order — no unbounded retry loops. |
| NFR5 | **Reproducibility**: the full system (API + DB + cache) shall start with a single `docker-compose up`. |
| NFR6 | **Testability**: core allocation logic shall be covered by automated unit tests exercising both success and conflict paths. |
| NFR7 | **Observability**: allocation and rollback events shall be loggable/inspectable without querying internal tables directly (log output or an audit endpoint). |

## 4. Assumptions & Constraints

- Single-node deployment; no distributed transaction coordination across services.
- Inventory items are pre-seeded; item creation/management is out of scope.
- Idempotency keys are scoped per-order and do not require cross-system coordination.
- Load testing simulates concurrency with a script or JMeter against a single running instance — not a distributed load test.

## 5. Decisions made since this was written

These were open questions when this document was first drafted. They've
since been resolved — see `docs/adr/` for the reasoning behind each:

- Partial allocation vs. all-or-nothing when supply is insufficient (FR4) — see ADR-0001.
- Idempotency key scope and expiry policy — see ADR-0002.
- Optimistic vs. pessimistic locking as the default strategy — pessimistic
  locking (`SELECT ... FOR UPDATE`) is the current implementation; a
  written comparison is still a stretch goal.
