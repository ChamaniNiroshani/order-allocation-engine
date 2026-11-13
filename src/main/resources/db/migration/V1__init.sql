-- Baseline schema, matching what Hibernate's ddl-auto=update produced up to now,
-- except transaction_log.before_state/after_state which are text instead of oid.

CREATE TABLE inventory_item (
    item_id            uuid    NOT NULL,
    quantity_available integer NOT NULL,
    quantity_reserved  integer NOT NULL,
    version            bigint,
    CONSTRAINT inventory_item_pkey PRIMARY KEY (item_id)
);

CREATE TABLE orders (
    order_id           uuid                     NOT NULL,
    item_id            uuid                     NOT NULL,
    quantity_requested integer,
    status             varchar(255),
    idempotency_key    varchar(255)             NOT NULL,
    created_at         timestamp(6) with time zone,
    failure_reason     varchar(255),
    request_hash       varchar(64),
    CONSTRAINT orders_pkey PRIMARY KEY (order_id),
    CONSTRAINT uk_orders_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT orders_quantity_requested_check CHECK (quantity_requested >= 1),
    CONSTRAINT orders_status_check CHECK (status IN
        ('PENDING', 'ALLOCATED', 'PARTIALLY_ALLOCATED', 'FAILED', 'ROLLED_BACK'))
);

CREATE TABLE allocation (
    allocation_id      uuid                     NOT NULL,
    order_id           uuid                     NOT NULL,
    item_id            uuid                     NOT NULL,
    quantity_allocated integer,
    status             varchar(255),
    "timestamp"        timestamp(6) with time zone,
    CONSTRAINT allocation_pkey PRIMARY KEY (allocation_id),
    CONSTRAINT allocation_status_check CHECK (status IN
        ('PENDING', 'ALLOCATED', 'PARTIALLY_ALLOCATED', 'FAILED', 'ROLLED_BACK'))
);

CREATE TABLE transaction_log (
    log_id        uuid                     NOT NULL,
    entity_type   varchar(255),
    entity_id     uuid,
    action        varchar(255),
    before_state  text,
    after_state   text,
    "timestamp"   timestamp(6) with time zone,
    CONSTRAINT transaction_log_pkey PRIMARY KEY (log_id)
);
