-- NMK Opticals
-- Migration 003: create the configured checkout inventory location.
--
-- TEST ONLY against the disposable local staging database first.
-- DO NOT execute against the client's live database.
--
-- This script does not create opening stock.
-- If MAIN_STORE already exists, it is left unchanged.
-- Review the verification query before proceeding.

INSERT INTO inventory_locations (code, name, active)
SELECT
    'MAIN_STORE',
    'NMK Opticals - Avalurpet',
    b'1'
WHERE NOT EXISTS (
    SELECT 1
    FROM inventory_locations
    WHERE code = 'MAIN_STORE'
);

-- Verify the location. Check that exactly one row is returned,
-- and that active = 1 before using it for checkout.
SELECT
    id,
    code,
    name,
    active,
    address_snapshot
FROM inventory_locations
WHERE code = 'MAIN_STORE';
