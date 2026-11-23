-- NMK Opticals
-- Migration 004: reconcile the existing partially migrated staging schema.
--
-- DRAFT ONLY. REVIEW BEFORE EXECUTION.
-- Intended only for the disposable local nmk_staging_restore database.
-- NEVER execute against the client's live database without a separate
-- production migration plan and explicit approval.
--
-- Existing inventory tables, constraints and indexes are preserved.
-- Existing AVP-01 location is reused; no location is inserted.
-- No opening stock is created.
-- products.stock_quantity is not modified.
-- Existing order reservation_type values are not rewritten.

-- 1. Add missing order inventory metadata.
ALTER TABLE customer_orders
    ADD COLUMN reservation_type VARCHAR(30) NULL,
    ADD COLUMN inventory_location_id BIGINT NULL;

-- 2. Make payment creation status consistent with the application default.
-- The inspected staging database currently contains zero orders.
ALTER TABLE customer_orders
    MODIFY COLUMN payment_creation_status
        VARCHAR(30) NOT NULL DEFAULT 'NOT_STARTED';

-- 3. Add missing variant snapshots to order items.
-- variant_id already exists and its foreign key is preserved.
ALTER TABLE order_items
    ADD COLUMN variant_sku VARCHAR(100) NULL,
    ADD COLUMN variant_name VARCHAR(150) NULL,
    ADD COLUMN variant_attributes_json VARCHAR(4000) NULL;

-- 4. Match the entity's prescription_json TEXT mapping.
ALTER TABLE order_items
    MODIFY COLUMN prescription_json TEXT NULL;

-- 5. Verification queries. Review results after approved execution.
SHOW COLUMNS FROM customer_orders;
SHOW COLUMNS FROM order_items;
SHOW CREATE TABLE customer_orders;
SHOW CREATE TABLE order_items;