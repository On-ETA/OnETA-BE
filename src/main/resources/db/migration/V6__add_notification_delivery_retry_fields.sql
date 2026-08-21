ALTER TABLE notification_deliveries
    ADD COLUMN next_attempt_at DATETIME NULL,
    ADD COLUMN last_error_code VARCHAR(64) NULL,
    ADD COLUMN last_error_message VARCHAR(1000) NULL;

CREATE INDEX idx_notification_delivery_retry
    ON notification_deliveries (status, next_attempt_at, id);
