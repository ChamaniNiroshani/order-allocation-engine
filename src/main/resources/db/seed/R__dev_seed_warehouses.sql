-- DEV DATA ONLY. Loaded when the dev profile adds classpath:db/seed to spring.flyway.locations (application-dev.properties).
-- Repeatable (R__) on purpose: it is not part of the versioned history, so it never blocks a real V6, V7, ...
-- Fixed ids make it safe to re-run and let docs/demo-fulfilment.ps1 refer to the same rows every time.
-- Expiry dates are relative to the day the script runs. After a few days: docker compose down -v, or edit this file (a new checksum re-runs it).
-- Scenario: SKU MILK-1L is ordered from Colombo. Colombo is nearest but holds too little for 60 and its stock expires soonest;
-- Kandy is next-nearest with a lot of stock; Galle has the freshest stock.

INSERT INTO warehouse (id, name, lat, lon) VALUES
    ('00000000-0000-0000-0000-0000000000c1', 'Colombo', 6.927100, 79.861200),
    ('00000000-0000-0000-0000-0000000000c2', 'Kandy',   7.290600, 80.633700),
    ('00000000-0000-0000-0000-0000000000c3', 'Galle',   6.053500, 80.221000)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, lat = EXCLUDED.lat, lon = EXCLUDED.lon;

INSERT INTO inventory_batch (id, sku, warehouse_id, qty_available, qty_reserved, expiry_date, version) VALUES
    -- Colombo: 40 units, two batches, one close to expiry
    ('00000000-0000-0000-0000-0000000000b1', 'MILK-1L', '00000000-0000-0000-0000-0000000000c1', 15, 0, CURRENT_DATE + 3,  0),
    ('00000000-0000-0000-0000-0000000000b2', 'MILK-1L', '00000000-0000-0000-0000-0000000000c1', 25, 0, CURRENT_DATE + 20, 0),
    -- Kandy: 100 units
    ('00000000-0000-0000-0000-0000000000b3', 'MILK-1L', '00000000-0000-0000-0000-0000000000c2', 100, 0, CURRENT_DATE + 10, 0),
    -- Galle: 30 units, the freshest
    ('00000000-0000-0000-0000-0000000000b4', 'MILK-1L', '00000000-0000-0000-0000-0000000000c3', 30, 0, CURRENT_DATE + 45, 0),
    -- Non-perishable (no expiry date): sorts last under FEFO
    ('00000000-0000-0000-0000-0000000000b5', 'RICE-5KG', '00000000-0000-0000-0000-0000000000c1', 50, 0, NULL, 0),
    ('00000000-0000-0000-0000-0000000000b6', 'RICE-5KG', '00000000-0000-0000-0000-0000000000c3', 50, 0, CURRENT_DATE + 90, 0),
    -- Already expired: must never be shipped
    ('00000000-0000-0000-0000-0000000000b7', 'MILK-1L', '00000000-0000-0000-0000-0000000000c1', 500, 0, CURRENT_DATE - 2, 0)
ON CONFLICT (id) DO UPDATE SET qty_available = EXCLUDED.qty_available, qty_reserved = EXCLUDED.qty_reserved,
                               expiry_date = EXCLUDED.expiry_date;
