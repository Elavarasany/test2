-- NMK Opticals
-- Migration 001: legacy product and order schema updates.
--
-- Based on the verified reference schema snapshot.
-- TEST ONLY against a disposable restored database first.
-- DO NOT execute against the client's live database.
--
-- Existing product stock is preserved.
-- Existing orders remain legacy orders (reservation_type stays NULL).
-- No inventory opening balances are created here.

-- 1. Product optimistic locking.
ALTER TABLE products
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 2. Order payment-creation and inventory metadata.
ALTER TABLE customer_orders
    ADD COLUMN payment_creation_status VARCHAR(30)
        NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN reservation_type VARCHAR(30) NULL,
    ADD COLUMN inventory_location_id BIGINT NULL;

-- Existing orders with a Razorpay order ID have already passed
-- the payment-order creation stage.
UPDATE customer_orders
SET payment_creation_status = 'CREATED'
WHERE razorpay_order_id IS NOT NULL
  AND TRIM(razorpay_order_id) <> '';

-- Intentionally do not populate reservation_type.
-- NULL is treated as legacy by the current application code.

-- 3. Variant snapshots on order items.
ALTER TABLE order_items
    ADD COLUMN variant_id BIGINT NULL,
    ADD COLUMN variant_sku VARCHAR(100) NULL,
    ADD COLUMN variant_name VARCHAR(150) NULL,
    ADD COLUMN variant_attributes_json VARCHAR(4000) NULL;

-- Preserve existing prescription data while widening the column.
ALTER TABLE order_items
    MODIFY COLUMN prescription_json TEXT NULL;