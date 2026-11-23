-- NMK Opticals — POS migration 014: atomic exchange audit and credit settlement.
--
-- MANUAL, ADDITIVE MIGRATION. The application does not execute this script automatically;
-- run it only after the target schema baseline and disposable staging rehearsal are approved.
-- Test on a disposable MySQL 8.4 schema first. Never run against the client's
-- live database without explicit approval, a verified backup, and a rollback plan.
-- Depends on migrations 005, 007, and 008.

CREATE TABLE pos_exchanges (
    id BIGINT NOT NULL AUTO_INCREMENT,
    original_sale_id BIGINT NOT NULL,
    return_id BIGINT NOT NULL,
    replacement_sale_id BIGINT NOT NULL,
    shift_id BIGINT NOT NULL,
    processed_by_username VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    returned_value DECIMAL(12,2) NOT NULL,
    replacement_value DECIMAL(12,2) NOT NULL,
    credit_applied DECIMAL(12,2) NOT NULL,
    actual_refund DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    amount_collected DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    idempotency_key VARCHAR(150) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT uk_pos_exchanges_return UNIQUE (return_id),
    CONSTRAINT uk_pos_exchanges_replacement_sale UNIQUE (replacement_sale_id),
    CONSTRAINT uk_pos_exchanges_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_pos_exchanges_original_sale
        FOREIGN KEY (original_sale_id) REFERENCES pos_sales (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pos_exchanges_return
        FOREIGN KEY (return_id) REFERENCES pos_returns (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pos_exchanges_replacement_sale
        FOREIGN KEY (replacement_sale_id) REFERENCES pos_sales (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pos_exchanges_shift
        FOREIGN KEY (shift_id) REFERENCES pos_shifts (id) ON DELETE RESTRICT,
    CONSTRAINT chk_pos_exchanges_amounts CHECK (
        returned_value >= 0 AND replacement_value >= 0
        AND credit_applied >= 0 AND actual_refund >= 0 AND amount_collected >= 0
        AND returned_value = credit_applied + actual_refund
        AND replacement_value = credit_applied + amount_collected
    ),
    INDEX idx_pos_exchanges_original_created (original_sale_id, created_at),
    INDEX idx_pos_exchanges_replacement_created (replacement_sale_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- This row is the explicit, immutable non-cash credit consumed by the replacement
-- sale. It is deliberately separate from pos_payments and pos_refund_payments:
-- exchange credit is neither customer tender nor money refunded.
CREATE TABLE pos_exchange_credit_settlements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    exchange_id BIGINT NOT NULL,
    replacement_sale_id BIGINT NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT uk_pos_exchange_credit_exchange UNIQUE (exchange_id),
    CONSTRAINT uk_pos_exchange_credit_replacement UNIQUE (replacement_sale_id),
    CONSTRAINT fk_pos_exchange_credit_exchange
        FOREIGN KEY (exchange_id) REFERENCES pos_exchanges (id) ON DELETE RESTRICT,
    CONSTRAINT fk_pos_exchange_credit_replacement
        FOREIGN KEY (replacement_sale_id) REFERENCES pos_sales (id) ON DELETE RESTRICT,
    CONSTRAINT chk_pos_exchange_credit_amount CHECK (amount > 0),
    INDEX idx_pos_exchange_credit_replacement (replacement_sale_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
