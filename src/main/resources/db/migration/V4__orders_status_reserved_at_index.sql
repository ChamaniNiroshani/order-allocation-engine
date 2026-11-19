-- The expiry job looks for RESERVED (and stuck PAID) orders older than the TTL, oldest first.
CREATE INDEX idx_orders_status_reserved_at ON orders (status, reserved_at);
