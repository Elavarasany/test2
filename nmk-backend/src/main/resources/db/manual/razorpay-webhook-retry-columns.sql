-- NMK Opticals: apply only to a verified staging restore first.
-- Required by RazorpayWebhookEvent retry metadata.
-- Do not execute against production without a separate migration approval.

ALTER TABLE razorpay_webhook_events
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at DATETIME NULL,
    ADD COLUMN processing_started_at DATETIME NULL;

-- Existing RECEIVED rows become immediately eligible for processing.
UPDATE razorpay_webhook_events
SET next_attempt_at = received_at
WHERE next_attempt_at IS NULL
  AND status IN ('RECEIVED', 'RETRY');

CREATE INDEX idx_razorpay_webhook_due
    ON razorpay_webhook_events (status, next_attempt_at, received_at);
