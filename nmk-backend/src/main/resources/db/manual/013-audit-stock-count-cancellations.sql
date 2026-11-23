-- NMK Opticals — POS migration 013: audit physical stock-count cancellations.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Apply after 012-distinguish-pos-voids.sql. Never run against the client's live
-- database without explicit approval, a verified backup and a reviewed migration plan.

ALTER TABLE stock_count_sessions
    ADD COLUMN cancelled_by_username VARCHAR(100) NULL,
    ADD COLUMN cancelled_at DATETIME(6) NULL;
