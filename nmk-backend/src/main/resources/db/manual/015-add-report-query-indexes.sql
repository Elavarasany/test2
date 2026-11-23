-- NMK Opticals — POS migration 015: composite indexes for operational reports.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Run once after migrations 005, 008 and 010. Do not run against the client's
-- live database without explicit approval and a verified backup.
--
-- Supports the report predicates:
--   pos_sales: location_id + status + created_at range
--   pos_returns: status + created_at range, then join to the originating sale
--   supplier_returns: purchase_order_id + created_at range

CREATE INDEX idx_pos_sales_location_status_created
    ON pos_sales (location_id, status, created_at, id);

CREATE INDEX idx_pos_returns_status_created_sale
    ON pos_returns (status, created_at, sale_id);

CREATE INDEX idx_supplier_returns_po_created
    ON supplier_returns (purchase_order_id, created_at);
