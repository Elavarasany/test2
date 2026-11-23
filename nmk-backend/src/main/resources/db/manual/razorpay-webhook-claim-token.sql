-- NMK Opticals: apply only to a verified local staging restore.
-- Adds ownership tracking for webhook processing attempts.
-- Do not execute against production without separate migration approval.

ALTER TABLE razorpay_webhook_events
    ADD COLUMN claim_token VARCHAR(36) NULL;
