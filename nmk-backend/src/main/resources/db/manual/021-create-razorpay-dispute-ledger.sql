-- NMK Opticals — migration 021: Razorpay dispute/chargeback ledger.
--
-- MANUAL, ADDITIVE MIGRATION. Apply only after reviewing the target schema.
-- Rehearse on a disposable MySQL 8.4 restore and take a verified backup first.
-- Do not run against the client's live database without explicit approval.
--
-- The table stores only dispute metadata required for operational follow-up.
-- Customer email, phone, VPA, card data and evidence documents are not copied.

CREATE TABLE IF NOT EXISTS razorpay_disputes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider_dispute_id VARCHAR(64) NOT NULL,
    provider_payment_id VARCHAR(64) NOT NULL,
    local_order_id BIGINT NULL,
    amount_paise BIGINT NOT NULL,
    amount_deducted_paise BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    reason_code VARCHAR(100) NULL,
    reason_description VARCHAR(500) NULL,
    respond_by DATETIME NULL,
    status VARCHAR(30) NOT NULL,
    phase VARCHAR(40) NULL,
    provider_created_at DATETIME NULL,
    last_provider_event_at DATETIME NULL,
    last_provider_event_id VARCHAR(150) NULL,
    last_provider_event_type VARCHAR(100) NULL,
    match_status VARCHAR(30) NOT NULL,
    match_message VARCHAR(500) NULL,
    last_synced_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_razorpay_dispute_provider_id (provider_dispute_id),
    KEY idx_razorpay_dispute_status_deadline (status, respond_by),
    KEY idx_razorpay_dispute_payment (provider_payment_id),
    KEY idx_razorpay_dispute_match (match_status, last_provider_event_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
