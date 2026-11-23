-- NMK Opticals — migration 017: provider refund reconciliation ledger.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Run after the customer_orders table exists. Do not run against the client's
-- live database without explicit approval and a verified backup.
--
-- Refunds initiated in Razorpay are reconciled from signed refund.created,
-- refund.processed and refund.failed webhooks. This migration does not initiate
-- provider refunds and does not represent bank settlement reconciliation.

ALTER TABLE customer_orders
    ADD COLUMN refund_status VARCHAR(30) NOT NULL DEFAULT 'NONE';

CREATE TABLE order_refunds (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    razorpay_refund_id VARCHAR(64) NOT NULL,
    razorpay_payment_id VARCHAR(64) NOT NULL,
    amount_paise BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_provider_event_id VARCHAR(150) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_refunds_razorpay_refund_id (razorpay_refund_id),
    KEY idx_order_refunds_order_status (order_id, status),
    KEY idx_order_refunds_payment_id (razorpay_payment_id),
    CONSTRAINT fk_order_refunds_order
        FOREIGN KEY (order_id) REFERENCES customer_orders(id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_order_refunds_amount CHECK (amount_paise > 0),
    CONSTRAINT chk_order_refunds_status CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED'))
) ENGINE=InnoDB;
