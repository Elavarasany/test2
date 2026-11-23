-- NMK Opticals — migration 018: shared API rate-limit buckets.
--
-- MANUAL, ADDITIVE MIGRATION. Test on a disposable MySQL 8.4 staging restore.
-- Apply before setting app.rate-limit.enabled=true. Do not run against the
-- client's live database without explicit approval and a verified backup.
--
-- The bucket key is a SHA-256 digest of route, socket peer address and window.
-- Raw client IP addresses are not stored. Provider webhooks are not throttled.

CREATE TABLE IF NOT EXISTS api_rate_limit_buckets (
    bucket_key CHAR(64) NOT NULL,
    window_start DATETIME(6) NOT NULL,
    request_count INT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (bucket_key),
    KEY idx_api_rate_limit_expiry (expires_at),
    CONSTRAINT chk_api_rate_limit_count CHECK (request_count > 0)
) ENGINE=InnoDB;
