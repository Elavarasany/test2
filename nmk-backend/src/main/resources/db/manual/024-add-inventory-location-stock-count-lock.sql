-- NMK Opticals — migration 024: inventory-location stock-count lock.
--
-- MANUAL, ADDITIVE MIGRATION.
-- Apply only to the isolated nmk_pos_manual_test database after reviewing
-- the target schema and taking an appropriate backup.
-- Do not run against the client's live database without explicit approval.
-- Flyway remains disabled until the legacy schema is baselined.
--
-- InventoryLocation maps this property to inventory_locations.stock_count_locked.
-- The inspected nmk_pos_manual_test schema does not currently contain it.
-- Existing locations remain unlocked by default; no stock quantities change.
--
-- Apply only when SHOW COLUMNS confirms the column is absent.

ALTER TABLE inventory_locations
    ADD COLUMN stock_count_locked BOOLEAN NOT NULL DEFAULT FALSE;
