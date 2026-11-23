-- NMK Opticals — migration 017: administrator-to-inventory-location assignments.
--
-- MANUAL, ADDITIVE MIGRATION. Apply only to the isolated POS manual-test database
-- after reviewing the target database and taking an appropriate backup.
-- Do not run against the client's live database without explicit approval.
-- Flyway remains disabled until the legacy schema is baselined.
--
-- This table backs Admin.assignedLocations in Admin.java. Each row grants one
-- staff account access to one inventory location. Global ADMIN accounts do not
-- need assignment rows; that rule is enforced by AdminLocationAccessService.
--
-- If this table already exists with a different definition, stop and review it
-- rather than assuming CREATE TABLE IF NOT EXISTS repairs the existing schema.

CREATE TABLE IF NOT EXISTS admin_location_assignments (
    admin_id BIGINT NOT NULL,
    location_id BIGINT NOT NULL,

    CONSTRAINT uk_admin_location_assignment
        UNIQUE (admin_id, location_id),

    KEY idx_admin_location_assignments_location (location_id),

    CONSTRAINT fk_admin_location_assignments_admin
        FOREIGN KEY (admin_id)
        REFERENCES admins (id)
        ON UPDATE CASCADE
        ON DELETE CASCADE,

    CONSTRAINT fk_admin_location_assignments_location
        FOREIGN KEY (location_id)
        REFERENCES inventory_locations (id)
        ON UPDATE CASCADE
        ON DELETE CASCADE
) ENGINE=InnoDB;
