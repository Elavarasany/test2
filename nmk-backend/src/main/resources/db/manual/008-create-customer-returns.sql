-- NMK Opticals — POS migration 008: customer returns and refund audit records.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Never run against the client's live database without explicit approval and backup.
-- Depends on POS sales migration 005 and register/shift migration 007.
-- Completed sales and their lines are preserved; this adds separate return history.

CREATE TABLE pos_returns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_number VARCHAR(80) NOT NULL,
    sale_id BIGINT NOT NULL,
    shift_id BIGINT NOT NULL,
    processed_by_username VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    refund_total DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    idempotency_key VARCHAR(150) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_pos_returns_number UNIQUE (return_number),
    CONSTRAINT uk_pos_returns_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_pos_returns_sale
        FOREIGN KEY (sale_id) REFERENCES pos_sales (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_pos_returns_shift
        FOREIGN KEY (shift_id) REFERENCES pos_shifts (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_pos_returns_refund_total CHECK (refund_total >= 0),
    INDEX idx_pos_returns_sale_created (sale_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pos_return_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_id BIGINT NOT NULL,
    sale_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_refund_amount DECIMAL(12,2) NOT NULL,
    refund_amount DECIMAL(12,2) NOT NULL,
    restock_disposition VARCHAR(20) NOT NULL,
    disposition_reason VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pos_return_items_return
        FOREIGN KEY (return_id) REFERENCES pos_returns (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_pos_return_items_sale_item
        FOREIGN KEY (sale_item_id) REFERENCES pos_sale_items (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_pos_return_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_pos_return_items_amounts
        CHECK (unit_refund_amount >= 0 AND refund_amount >= 0),
    CONSTRAINT chk_pos_return_items_disposition
        CHECK (restock_disposition IN ('RESTOCK', 'DAMAGED', 'DISCARD')),
    INDEX idx_pos_return_items_return (return_id),
    INDEX idx_pos_return_items_sale_item (sale_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pos_refund_payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_id BIGINT NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    transaction_reference VARCHAR(150) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_pos_refund_payments_return
        FOREIGN KEY (return_id) REFERENCES pos_returns (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_pos_refund_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_pos_refund_payments_method
        CHECK (payment_method IN ('CASH', 'UPI', 'CARD')),
    INDEX idx_pos_refund_payments_return (return_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
