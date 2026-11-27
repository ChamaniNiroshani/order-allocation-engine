-- An Idempotency-Key belongs to ONE flow: POST /api/orders, /api/checkout or /api/fulfilments (docs/adr/0002).
-- Orders and checkouts share the orders table and fulfilments have their own, so no constraint on those tables can stop
-- two flows from using one key. Every request that stores something inserts its key here in the same transaction.

CREATE TABLE idempotency_key (
    idempotency_key varchar(255) NOT NULL,
    flow            varchar(16)  NOT NULL,
    CONSTRAINT idempotency_key_pkey PRIMARY KEY (idempotency_key),
    CONSTRAINT idempotency_key_flow_check CHECK (flow IN ('ORDER', 'CHECKOUT', 'FULFILMENT'))
);

-- Existing orders. Only a checkout sets reserved_at; a checkout rejected for lack of stock has none, and is told apart
-- from a rejected direct order by its exact reason (the direct one adds how many units were available).
INSERT INTO idempotency_key (idempotency_key, flow)
SELECT idempotency_key,
       CASE WHEN reserved_at IS NOT NULL OR failure_reason = 'Insufficient inventory' THEN 'CHECKOUT' ELSE 'ORDER' END
  FROM orders;

-- Until now one key could be stored by an order AND a fulfilment. The order keeps such a key.
INSERT INTO idempotency_key (idempotency_key, flow)
SELECT idempotency_key, 'FULFILMENT'
  FROM fulfilment_order
    ON CONFLICT (idempotency_key) DO NOTHING;
