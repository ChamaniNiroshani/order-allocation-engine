-- Multi-warehouse fulfilment: a flow NEXT TO inventory_item / orders, not a replacement (docs/adr/0004).

CREATE TABLE warehouse (
    id   uuid          NOT NULL,
    name varchar(255)  NOT NULL,
    lat  numeric(9, 6) NOT NULL,
    lon  numeric(9, 6) NOT NULL,
    CONSTRAINT warehouse_pkey PRIMARY KEY (id),
    CONSTRAINT warehouse_lat_check CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT warehouse_lon_check CHECK (lon BETWEEN -180 AND 180)
);

CREATE TABLE inventory_batch (
    id            uuid         NOT NULL,
    sku           varchar(64)  NOT NULL,
    warehouse_id  uuid         NOT NULL,
    qty_available integer      NOT NULL,
    qty_reserved  integer      NOT NULL DEFAULT 0,
    expiry_date   date,
    version       bigint,
    CONSTRAINT inventory_batch_pkey PRIMARY KEY (id),
    CONSTRAINT inventory_batch_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT inventory_batch_qty_available_check CHECK (qty_available >= 0),
    CONSTRAINT inventory_batch_qty_reserved_check CHECK (qty_reserved >= 0)
);
CREATE INDEX idx_inventory_batch_sku ON inventory_batch (sku);

CREATE TABLE fulfilment_order (
    id              uuid                     NOT NULL,
    sku             varchar(64)              NOT NULL,
    quantity        integer                  NOT NULL,
    delivery_lat    numeric(9, 6)            NOT NULL,
    delivery_lon    numeric(9, 6)            NOT NULL,
    customer_tier   varchar(16)              NOT NULL,
    strategy        varchar(16)              NOT NULL,
    status          varchar(16)              NOT NULL,
    failure_reason  varchar(255),
    idempotency_key varchar(255)             NOT NULL,
    request_hash    varchar(64),
    created_at      timestamp(6) with time zone NOT NULL,
    CONSTRAINT fulfilment_order_pkey PRIMARY KEY (id),
    CONSTRAINT uk_fulfilment_order_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fulfilment_order_quantity_check CHECK (quantity >= 1),
    CONSTRAINT fulfilment_order_tier_check CHECK (customer_tier IN ('PRIORITY', 'STANDARD')),
    CONSTRAINT fulfilment_order_strategy_check CHECK (strategy IN ('NEAREST', 'FEFO', 'SPLIT')),
    CONSTRAINT fulfilment_order_status_check CHECK (status IN ('ALLOCATED', 'FAILED'))
);

CREATE TABLE fulfilment_line (
    id                  uuid    NOT NULL,
    fulfilment_order_id uuid    NOT NULL,
    batch_id            uuid    NOT NULL,
    warehouse_id        uuid    NOT NULL,
    quantity            integer NOT NULL,
    CONSTRAINT fulfilment_line_pkey PRIMARY KEY (id),
    CONSTRAINT fulfilment_line_order_fk FOREIGN KEY (fulfilment_order_id) REFERENCES fulfilment_order (id),
    CONSTRAINT fulfilment_line_batch_fk FOREIGN KEY (batch_id) REFERENCES inventory_batch (id),
    CONSTRAINT fulfilment_line_warehouse_fk FOREIGN KEY (warehouse_id) REFERENCES warehouse (id),
    CONSTRAINT fulfilment_line_quantity_check CHECK (quantity >= 1)
);
CREATE INDEX idx_fulfilment_line_order ON fulfilment_line (fulfilment_order_id);
