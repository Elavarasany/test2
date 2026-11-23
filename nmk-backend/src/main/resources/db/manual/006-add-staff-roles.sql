-- NMK Opticals — POS migration 006: staff role and account status.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable staging restore first.
-- Do not execute against the client/live database without explicit approval.
-- Existing admins are preserved and receive the ADMIN role by default.
-- Password hashes are not changed. No account is disabled by this migration.
-- Run exactly once after backing up the target schema.

ALTER TABLE admins
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD CONSTRAINT chk_admins_role
        CHECK (role IN ('ADMIN', 'MANAGER', 'CASHIER'));

CREATE INDEX idx_admins_role_enabled ON admins (role, enabled);
