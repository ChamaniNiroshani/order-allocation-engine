# ADR-0006: Checkout is an orchestrated saga

Date: 2026-11-19 (written 2026-11-27 from the code and commit history)
Status: Accepted

## Context

Checkout reserves stock, charges a payment gateway, then confirms the order. The payment is a remote call that can
decline, be slow, or time out after taking the money. It cannot join a database transaction, and no row lock should
be held while waiting for it.

## Options

1. **One database transaction around everything.** Simple, but it holds a transaction and row locks during the
   remote call, and a rollback cannot undo a charge.
2. **Choreography:** each step publishes an event and the next one reacts. Steps are decoupled, but it needs a broker
   and reliable event publishing, and the order of steps is spread over several consumers.
3. **Orchestration:** one class runs the steps and decides how to compensate.

## Decision

Use orchestration (option 3). `CheckoutOrchestrator` runs synchronously inside the one service, and:

- each database step is its own short transaction (`CheckoutSteps`); the charge runs with none open;
- the order status is the saga state: `PENDING → RESERVED → PAID → CONFIRMED`, or `CANCELLED` / `EXPIRED`;
- every status change after the order is reserved locks the order row and checks the status first;
- a decline releases the stock; a timeout (`payment.timeout`) requests a refund by idempotency key, then releases it;
- a scheduled job (`SKIP LOCKED`) expires stale `RESERVED` orders, requests a refund for each after the expiry
  commits, and confirms stale `PAID` ones.

## Consequences

- The whole flow can be read in one place, and no broker has to be run.
- Correctness depends on the gateway deduplicating by idempotency key: refunding a key that was never charged, or
  refunding twice, must do nothing. The gateway in this repo is a fake that keeps its state in memory.
- The caller waits for the payment: a slow gateway makes a slow request, up to `payment.timeout`.
- A crash leaves an order `RESERVED` or `PAID` until the job handles it after `checkout.reservation-ttl`. The
  app-kill runs in [chaos.md](../chaos.md) show that recovery. They were run before refund-on-expiry was added.
- The refund after an expiry is a dual write and is **not retried**
  ([#28](https://github.com/ChamaniNiroshani/order-allocation-engine/issues/28)).
- Splitting the steps into separate services would mean revisiting this decision.
