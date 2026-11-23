-- NMK Opticals — POS migration 007: registers, cashier shifts and cash drawer ledger.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Never run against the client's live database without explicit approval and backup.
-- Existing POS sales remain valid with NULL shift_id; all new checkout requests
-- are required by the application to reference an OPEN shift.
-- Run once after migrations 002 and 005, and after inventory locations exist.

CREATE TABLE pos_registers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    register_code VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_pos_register_location_code UNIQUE (location_id, register_code),
    CONSTRAINT fk_pos_register_location
        FOREIGN KEY (location_id) REFERENCES inventory_locations (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pos_shifts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    register_id BIGINT NOT NULL,
    opened_by_username VARCHAR(100) NOT NULL,
    closed_by_username VARCHAR(100) NULL,
    opening_float DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    expected_cash DECIMAL(12,2) NULL,
    closing_cash_count DECIMAL(12,2) NULL,
    cash_variance DECIMAL(12,2) NULL,
    closing_reason VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    idempotency_key VARCHAR(150) NOT NULL,
    opened_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    closed_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_pos_shifts_open_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_pos_shifts_register
        FOREIGN KEY (register_id) REFERENCES pos_registers (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_pos_shifts_amounts
        CHECK (opening_float >= 0
            AND (expected_cash IS NULL OR expected_cash >= 0)
            AND (closing_cash_count IS NULL OR closing_cash_count >= 0)),
    INDEX idx_pos_shifts_register_status (register_id, status),
    INDEX idx_pos_shifts_cashier_opened (opened_by_username, opened_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE pos_cash_movements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    shift_id BIGINT NOT NULL,
    movement_type VARCHAR(30) NOT NULL,
    direction VARCHAR(3) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    reference_type VARCHAR(40) NULL,
    reference_id BIGINT NULL,
    idempotency_key VARCHAR(150) NOT NULL,
    actor_username VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_pos_cash_movement_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_pos_cash_movements_shift
        FOREIGN KEY (shift_id) REFERENCES pos_shifts (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_pos_cash_movements_amount CHECK (amount > 0),
    CONSTRAINT chk_pos_cash_movements_direction CHECK (direction IN ('IN', 'OUT')),
    INDEX idx_pos_cash_movements_shift_created (shift_id, created_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE pos_sales
    ADD COLUMN shift_id BIGINT NULL,
    ADD CONSTRAINT fk_pos_sales_shift
        FOREIGN KEY (shift_id) REFERENCES pos_shifts (id)
        ON DELETE RESTRICT,
    ADD INDEX idx_pos_sales_shift (shift_id);

-- One MAIN register is provisioned for every currently active shop location.
-- Future locations need an explicit register created by an administrator.
INSERT INTO pos_registers (location_id, register_code, name, active)
SELECT location.id, 'MAIN', 'Main Register', TRUE
FROM inventory_locations location
WHERE location.active = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM pos_registers existing
      WHERE existing.location_id = location.id
        AND existing.register_code = 'MAIN'
  );
