-- NMK Opticals — migration 019: idempotent online refund initiation.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Run after migration 017. Do not run against the client's live database
-- without explicit approval, exact schema comparison and a verified backup.
--
-- Provider refund ID is unknown until Razorpay responds. The local request key
-- and receipt reserve the amount before the external call and allow safe retry
-- with the same X-Refund-Idempotency key if the response is lost.

ALTER TABLE order_refunds
    MODIFY COLUMN razorpay_refund_id VARCHAR(64) NULL,
    ADD COLUMN request_idempotency_key VARCHAR(80) NULL,
    ADD COLUMN receipt VARCHAR(64) NULL,
    ADD COLUMN reason VARCHAR(255) NULL,
    ADD COLUMN initiated_by VARCHAR(100) NULL,
    ADD UNIQUE KEY uk_order_refunds_request_key (request_idempotency_key),
    ADD UNIQUE KEY uk_order_refunds_payment_receipt (razorpay_payment_id, receipt);
