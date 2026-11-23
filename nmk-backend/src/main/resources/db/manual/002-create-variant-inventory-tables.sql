-- NMK Opticals
-- Migration 002: variant-based inventory schema.
--
-- TEST ONLY against a disposable restored database first.
-- DO NOT execute against the client's live database.
--
-- This migration creates tables only.
-- It does NOT create product variants, inventory locations,
-- inventory balances, or opening stock.
-- Existing products.stock_quantity remains unchanged.

-- 1. Product variants.
CREATE TABLE product_variants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    sku VARCHAR(100) NOT NULL,
    barcode VARCHAR(100) NULL,
    variant_name VARCHAR(255) NOT NULL,
    attributes JSON NOT NULL,
    selling_price_override DECIMAL(12,2) NULL,
    active BIT(1) NOT NULL DEFAULT b'1',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT uk_product_variants_sku UNIQUE (sku),
    CONSTRAINT uk_product_variants_barcode UNIQUE (barcode),
    CONSTRAINT fk_product_variants_product
        FOREIGN KEY (product_id) REFERENCES products (id),

    INDEX idx_product_variants_product_id (product_id)
) ENGINE=InnoDB;

-- 2. Inventory locations.
CREATE TABLE inventory_locations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(40) NOT NULL,
    name VARCHAR(150) NOT NULL,
    address_snapshot VARCHAR(1000) NULL,
    active BIT(1) NOT NULL DEFAULT b'1',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    CONSTRAINT uk_inventory_locations_code UNIQUE (code)
) ENGINE=InnoDB;

-- 3. Inventory balances.
CREATE TABLE inventory_balances (
    id BIGINT NOT NULL AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    on_hand_quantity INT NOT NULL DEFAULT 0,
    reserved_quantity INT NOT NULL DEFAULT 0,
    reorder_level INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT uk_inventory_balance_location_variant
        UNIQUE (location_id, variant_id),

    CONSTRAINT fk_inventory_balances_location
        FOREIGN KEY (location_id) REFERENCES inventory_locations (id),

    CONSTRAINT fk_inventory_balances_variant
        FOREIGN KEY (variant_id) REFERENCES product_variants (id),

    CONSTRAINT chk_inventory_balances_quantities
        CHECK (
            on_hand_quantity >= 0
            AND reserved_quantity >= 0
            AND reserved_quantity <= on_hand_quantity
            AND reorder_level >= 0
        ),

    INDEX idx_inventory_balances_variant_id (variant_id)
) ENGINE=InnoDB;

-- 4. Stock movement audit trail.
CREATE TABLE stock_movements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    location_id BIGINT NOT NULL,
    variant_id BIGINT NOT NULL,
    movement_type VARCHAR(40) NOT NULL,
    quantity_delta INT NOT NULL DEFAULT 0,
    reserved_delta INT NOT NULL DEFAULT 0,
    on_hand_after INT NOT NULL,
    reserved_after INT NOT NULL,
    reference_type VARCHAR(40) NULL,
    reference_id BIGINT NULL,
    idempotency_key VARCHAR(150) NOT NULL,
    actor_staff_id BIGINT NULL,
    actor_admin_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT uk_stock_movements_idempotency
        UNIQUE (idempotency_key),

    CONSTRAINT fk_stock_movements_location
        FOREIGN KEY (location_id) REFERENCES inventory_locations (id),

    CONSTRAINT fk_stock_movements_variant
        FOREIGN KEY (variant_id) REFERENCES product_variants (id),

    CONSTRAINT fk_stock_movements_actor_admin
        FOREIGN KEY (actor_admin_id) REFERENCES admins (id),

    CONSTRAINT chk_stock_movements_quantities
        CHECK (
            on_hand_after >= 0
            AND reserved_after >= 0
            AND reserved_after <= on_hand_after
        ),

    INDEX idx_stock_movements_location_variant
        (location_id, variant_id, created_at),

    INDEX idx_stock_movements_reference
        (reference_type, reference_id),

    INDEX idx_stock_movements_actor_admin
        (actor_admin_id)
) ENGINE=InnoDB;