-- NMK Opticals — migration 020: read-only Razorpay settlement reconciliation.
--
-- MANUAL, ADDITIVE MIGRATION. Apply only after reviewing the exact target schema.
-- Rehearse on a disposable MySQL 8.4 restore and take a verified backup first.
-- Do not run against the client's live database without explicit approval.
--
-- These tables retain provider settlement batches and their transaction rows.
-- "PROVIDER_RECONCILED" means the Razorpay report balances and its payment/refund
-- rows match local records. It does NOT mean the bank statement was matched.

CREATE TABLE IF NOT EXISTS razorpay_settlements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider_settlement_id VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    amount_paise BIGINT NOT NULL,
    fees_paise BIGINT NOT NULL,
    tax_paise BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    utr VARCHAR(100) NULL,
    provider_created_at DATETIME NULL,
    settled_at DATETIME NULL,
    last_synced_at DATETIME NOT NULL,
    reconciliation_status VARCHAR(30) NOT NULL,
    total_entries INT NOT NULL DEFAULT 0,
    matched_entries INT NOT NULL DEFAULT 0,
    unmatched_entries INT NOT NULL DEFAULT 0,
    manual_review_entries INT NOT NULL DEFAULT 0,
    pending_entries INT NOT NULL DEFAULT 0,
    net_rows_paise BIGINT NOT NULL DEFAULT 0,
    totals_balanced BOOLEAN NOT NULL DEFAULT FALSE,
    raw_payload TEXT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_razorpay_settlement_provider_id (provider_settlement_id),
    KEY idx_razorpay_settlement_status_date (status, provider_created_at),
    KEY idx_razorpay_settlement_reconciliation (reconciliation_status, last_synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS razorpay_settlement_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    settlement_id BIGINT NOT NULL,
    provider_entity_id VARCHAR(64) NOT NULL,
    entry_type VARCHAR(30) NOT NULL,
    debit_paise BIGINT NOT NULL DEFAULT 0,
    credit_paise BIGINT NOT NULL DEFAULT 0,
    amount_paise BIGINT NOT NULL DEFAULT 0,
    fee_paise BIGINT NOT NULL DEFAULT 0,
    tax_paise BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    on_hold BOOLEAN NOT NULL DEFAULT FALSE,
    settled BOOLEAN NOT NULL DEFAULT FALSE,
    provider_created_at DATETIME NULL,
    provider_settled_at DATETIME NULL,
    payment_id VARCHAR(64) NULL,
    order_id VARCHAR(64) NULL,
    settlement_utr VARCHAR(100) NULL,
    method VARCHAR(30) NULL,
    dispute_id VARCHAR(64) NULL,
    match_status VARCHAR(30) NOT NULL,
    match_message VARCHAR(500) NULL,
    local_order_id BIGINT NULL,
    local_refund_id BIGINT NULL,
    local_dispute_id BIGINT NULL,
    raw_payload TEXT NULL,
    created_at DATETIME NOT NULL,
    last_synced_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_razorpay_settlement_entry (settlement_id, provider_entity_id, entry_type),
    KEY idx_settlement_entry_payment (payment_id),
    KEY idx_settlement_entry_order (order_id),
    KEY idx_settlement_entry_match (match_status, settlement_id),
    CONSTRAINT fk_razorpay_settlement_entry_batch
        FOREIGN KEY (settlement_id) REFERENCES razorpay_settlements(id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
