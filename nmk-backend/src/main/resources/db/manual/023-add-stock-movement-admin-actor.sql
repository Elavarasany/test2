-- NMK Opticals
-- Migration 023: separate admin attribution for stock movements.
--
-- TEST ONLY against a disposable restored database first.
-- DO NOT execute against the client's live database.
--
-- Apply once to databases created before migration 023.
-- Fresh databases created with migration 002 already contain this column,
-- index, and foreign key; do not run this migration on those databases.

ALTER TABLE stock_movements
    ADD COLUMN actor_admin_id BIGINT NULL,
    ADD KEY idx_stock_movements_actor_admin (actor_admin_id),
    ADD CONSTRAINT fk_stock_movements_actor_admin
        FOREIGN KEY (actor_admin_id)
        REFERENCES admins(id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT;
