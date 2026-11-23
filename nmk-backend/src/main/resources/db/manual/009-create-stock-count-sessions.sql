-- NMK Opticals — POS migration 009: frozen physical stock count sessions.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Never run against the client's live database without explicit approval and backup.
-- Depends on inventory migration 002 and variant/inventory tables.

ALTER TABLE inventory_locations
    ADD COLUMN stock_count_locked BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE stock_count_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COUNTING',
    reason VARCHAR(500) NOT NULL,
    started_by_username VARCHAR(100) NOT NULL,
    submitted_by_username VARCHAR(100) NULL,
    posted_by_username VARCHAR(100) NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    submitted_at DATETIME(6) NULL,
    posted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_stock_count_sessions_location
        FOREIGN KEY (location_id) REFERENCES inventory_locations (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_stock_count_session_status
        CHECK (status IN ('COUNTING', 'READY_FOR_REVIEW', 'POSTED', 'CANCELLED')),
    INDEX idx_stock_count_location_status (location_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE stock_count_lines (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    snapshot_on_hand INT NOT NULL,
    counted_quantity INT NULL,
    counted_by_username VARCHAR(100) NULL,
    counted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_stock_count_session_variant UNIQUE (session_id, variant_id),
    CONSTRAINT fk_stock_count_lines_session
        FOREIGN KEY (session_id) REFERENCES stock_count_sessions (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_stock_count_lines_variant
        FOREIGN KEY (variant_id) REFERENCES product_variants (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_stock_count_line_quantities
        CHECK (snapshot_on_hand >= 0 AND (counted_quantity IS NULL OR counted_quantity >= 0)),
    INDEX idx_stock_count_lines_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
