-- Checkout saga: new order states, saga timestamps, payment reference and a unit price.

ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check CHECK (status IN
    ('PENDING', 'ALLOCATED', 'PARTIALLY_ALLOCATED', 'FAILED', 'ROLLED_BACK',
     'RESERVED', 'PAID', 'CONFIRMED', 'CANCELLED', 'EXPIRED'));

ALTER TABLE orders ADD COLUMN reserved_at timestamp(6) with time zone;
ALTER TABLE orders ADD COLUMN updated_at  timestamp(6) with time zone;
ALTER TABLE orders ADD COLUMN payment_id  varchar(255);

ALTER TABLE inventory_item ADD COLUMN unit_price numeric(12,2) NOT NULL DEFAULT 10.00;
