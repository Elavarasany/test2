-- NMK Opticals — POS migration 012: distinguish full sale voids from customer returns.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Never run against the client's live database without explicit approval and backup.

ALTER TABLE pos_returns
    ADD COLUMN return_type VARCHAR(20) NOT NULL DEFAULT 'RETURN';

ALTER TABLE pos_returns
    ADD CONSTRAINT chk_pos_returns_type
    CHECK (return_type IN ('RETURN', 'VOID'));
