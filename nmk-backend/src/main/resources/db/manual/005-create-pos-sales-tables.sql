-- NMK Opticals
-- Migration 005: POS sale and payment records.
--
-- MANUAL MIGRATION. Review and test against a disposable staging database first.
-- DO NOT execute against the client's live database without an approved migration plan.
-- This migration creates POS transaction tables only. It does not create opening stock.
-- MySQL DDL implicitly commits; take a database backup before running it.

CREATE TABLE pos_sales (
    id BIGINT NOT NULL AUTO_INCREMENT,
    invoice_number VARCHAR(80) NOT NULL,
    customer_name VARCHAR(150) NULL,
    customer_phone VARCHAR(30) NULL,
    location_id BIGINT NOT NULL,
    cashier_username VARCHAR(100) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL,
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    grand_total DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    idempotency_key VARCHAR(150) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT uk_pos_sales_invoice_number UNIQUE (invoice_number),
    CONSTRAINT uk_pos_sales_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_pos_sales_location
        FOREIGN KEY (location_id) REFERENCES inventory_locations (id),
    CONSTRAINT chk_pos_sales_amounts
        CHECK (subtotal >= 0 AND discount_amount >= 0
            AND discount_amount <= subtotal AND grand_total >= 0
            AND grand_total = subtotal - discount_amount),
    INDEX idx_pos_sales_created_at (created_at),
    INDEX idx_pos_sales_location (location_id),
    INDEX idx_pos_sales_cashier (cashier_username)
) ENGINE=InnoDB;

CREATE TABLE pos_sale_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sale_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    sku VARCHAR(100) NULL,
    product_name VARCHAR(200) NOT NULL,
    variant_name VARCHAR(255) NULL,
    variant_attributes_json TEXT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    line_total DECIMAL(12,2) NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_pos_sale_items_sale
        FOREIGN KEY (sale_id) REFERENCES pos_sales (id),
    CONSTRAINT fk_pos_sale_items_product
        FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_pos_sale_items_variant
        FOREIGN KEY (variant_id) REFERENCES product_variants (id),
    CONSTRAINT chk_pos_sale_items_values
        CHECK (quantity > 0 AND unit_price >= 0 AND line_total >= 0),
    INDEX idx_pos_sale_items_sale (sale_id),
    INDEX idx_pos_sale_items_product (product_id),
    INDEX idx_pos_sale_items_variant (variant_id)
) ENGINE=InnoDB;

CREATE TABLE pos_payments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sale_id BIGINT NOT NULL,
    payment_method VARCHAR(20) NOT NULL,
    amount DECIMAL(12,2) NOT NULL,
    tendered_amount DECIMAL(12,2) NULL,
    change_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    transaction_reference VARCHAR(150) NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_pos_payments_sale
        FOREIGN KEY (sale_id) REFERENCES pos_sales (id),
    CONSTRAINT chk_pos_payments_amounts
        CHECK (amount > 0 AND change_amount >= 0
            AND (tendered_amount IS NULL OR tendered_amount >= amount)),
    INDEX idx_pos_payments_sale (sale_id)
) ENGINE=InnoDB;
