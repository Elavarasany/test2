-- NMK Opticals — POS migration 010: suppliers, purchase orders, goods receipts and supplier returns.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Never run against the client's live database without explicit approval and backup.
-- Depends on inventory migration 002 and product variants.

CREATE TABLE suppliers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    -- Keep supplier identity case-insensitive even if the database default collation changes.
    name VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
    contact_name VARCHAR(120) NULL,
    phone VARCHAR(30) NULL,
    email VARCHAR(160) NULL,
    address VARCHAR(500) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_suppliers_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE purchase_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_number VARCHAR(80) NOT NULL,
    supplier_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by_username VARCHAR(100) NOT NULL,
    notes VARCHAR(500) NULL,
    subtotal DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    ordered_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_purchase_order_number UNIQUE (order_number),
    CONSTRAINT uk_purchase_order_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_purchase_orders_supplier
        FOREIGN KEY (supplier_id) REFERENCES suppliers (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_purchase_orders_location
        FOREIGN KEY (location_id) REFERENCES inventory_locations (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_purchase_orders_status
        CHECK (status IN ('DRAFT', 'ORDERED', 'PARTIALLY_RECEIVED', 'RECEIVED', 'CANCELLED')),
    CONSTRAINT chk_purchase_orders_subtotal CHECK (subtotal >= 0),
    INDEX idx_purchase_orders_location_status (location_id, status),
    INDEX idx_purchase_orders_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE purchase_order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    purchase_order_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    ordered_quantity INT NOT NULL,
    received_quantity INT NOT NULL DEFAULT 0,
    returned_quantity INT NOT NULL DEFAULT 0,
    unit_cost DECIMAL(12,2) NOT NULL,
    line_total DECIMAL(14,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_purchase_order_variant UNIQUE (purchase_order_id, variant_id),
    CONSTRAINT fk_purchase_order_items_order
        FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_purchase_order_items_variant
        FOREIGN KEY (variant_id) REFERENCES product_variants (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_purchase_order_item_quantities
        CHECK (ordered_quantity > 0 AND received_quantity >= 0
            AND received_quantity <= ordered_quantity
            AND returned_quantity >= 0 AND returned_quantity <= received_quantity),
    CONSTRAINT chk_purchase_order_item_cost
        CHECK (unit_cost > 0 AND line_total >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE goods_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    receipt_number VARCHAR(80) NOT NULL,
    purchase_order_id BIGINT NOT NULL,
    received_by_username VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_goods_receipt_number UNIQUE (receipt_number),
    CONSTRAINT uk_goods_receipt_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_goods_receipts_order
        FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT,
    INDEX idx_goods_receipts_po_created (purchase_order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE goods_receipt_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    goods_receipt_id BIGINT NOT NULL,
    purchase_order_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_cost DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_goods_receipt_po_item UNIQUE (goods_receipt_id, purchase_order_item_id),
    CONSTRAINT fk_goods_receipt_items_receipt
        FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipts (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_goods_receipt_items_po_item
        FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_goods_receipt_item_values CHECK (quantity > 0 AND unit_cost > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE supplier_returns (
    id BIGINT NOT NULL AUTO_INCREMENT,
    return_number VARCHAR(80) NOT NULL,
    purchase_order_id BIGINT NOT NULL,
    processed_by_username VARCHAR(100) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_supplier_return_number UNIQUE (return_number),
    CONSTRAINT uk_supplier_return_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_supplier_returns_order
        FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders (id)
        ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE supplier_return_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    supplier_return_id BIGINT NOT NULL,
    purchase_order_item_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_cost DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_supplier_return_po_item UNIQUE (supplier_return_id, purchase_order_item_id),
    CONSTRAINT fk_supplier_return_items_return
        FOREIGN KEY (supplier_return_id) REFERENCES supplier_returns (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_supplier_return_items_po_item
        FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items (id)
        ON DELETE RESTRICT,
    CONSTRAINT chk_supplier_return_item_values CHECK (quantity > 0 AND unit_cost > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
