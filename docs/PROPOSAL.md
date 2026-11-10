# Project Proposal: Order Allocation & Transaction Engine

## 1. Background

While working on **FabFlow** (a multi-tenant B2B2C SaaS platform for CNC
machining/fabrication), it became clear that allocating incoming customer
orders against limited machine capacity and material inventory is a hard
problem the moment more than one request can arrive at the same time.
A naive "check stock, then decrement" approach can oversell capacity under
concurrent access, and a failed allocation partway through can leave data
in an inconsistent state with no record of what happened.

FabFlow's own allocation logic is intentionally simple, scoped to what the
industry-project module needs. This project exists to go deep on the
underlying problem in isolation: **how do you allocate a limited resource
across concurrent requests, safely, and leave behind a provable record of
every decision?**

## 2. Problem Statement

Build a backend engine that accepts orders against a limited resource pool
and guarantees:
- No resource is ever over-allocated, regardless of how many requests
  arrive concurrently.
- Every allocation, failure, and rollback is recorded in an immutable,
  reconstructable audit trail.
- A retried request never causes a duplicate allocation.
- A partially failed multi-step allocation can be safely compensated
  (rolled back) without leaving orphaned state.

## 3. Objectives

| # | Objective |
|---|-----------|
| O1 | Implement and compare two concurrency-control strategies (optimistic vs. pessimistic locking) for resource allocation |
| O2 | Guarantee zero over-allocation under concurrent load, proven with a load test |
| O3 | Implement idempotent request handling |
| O4 | Implement an append-only transaction ledger sufficient to reconstruct any order's full history |
| O5 | Implement a compensation (rollback) flow for partial failures |
| O6 | Package the system so it runs reproducibly via Docker and is deployed to a public URL |

## 4. Scope

**In scope**
- REST API for order submission and inventory allocation
- Optimistic and pessimistic locking implementations, benchmarked against each other
- Idempotency key handling
- Append-only transaction log / audit trail
- Compensation flow for partial failures
- Redis caching for inventory reads
- Dockerized local environment
- CI pipeline (build + test on push)
- Automated tests for the core allocation logic

**Out of scope**
- Frontend/UI (API-only; interaction via Postman/curl/tests)
- Multi-service/microservices architecture
- Message queue-based processing (may be added later as a stretch goal)
- Multi-tenancy (FabFlow already covers that ground separately)

## 5. Success Criteria

- A documented load test shows 0 over-allocations under N concurrent
  requests, contrasted against a version without proper locking.
- Given any order ID, its full history (allocation, retries, rollback) can
  be reconstructed from the transaction log alone.
- The system runs end-to-end with a single `docker-compose up`.
- A live deployed instance and a case-study README exist by the project
  deadline.

## 6. Timeline

Four weeks, ~3–4 hrs/day. See `docs/PROJECT-PLAN.md` (or the project
tracker) for the week-by-week breakdown.

## 7. Tech Stack

Java, Spring Boot, Spring Data JPA, PostgreSQL, Redis, Docker,
GitHub Actions, JUnit + Mockito.
