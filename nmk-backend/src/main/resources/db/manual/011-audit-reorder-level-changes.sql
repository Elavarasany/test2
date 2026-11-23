-- NMK Opticals — POS migration 011: audit metadata for location-specific reorder thresholds.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Never run against the client's live database without explicit approval and backup.
-- The reorder_level column already exists in the inventory balance schema.

ALTER TABLE inventory_balances
    ADD COLUMN reorder_level_updated_by VARCHAR(100) NULL,
    ADD COLUMN reorder_level_updated_at DATETIME(6) NULL;

CREATE INDEX idx_inventory_balance_location_reorder
    ON inventory_balances (location_id, reorder_level);
