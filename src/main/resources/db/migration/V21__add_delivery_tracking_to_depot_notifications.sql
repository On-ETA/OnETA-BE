ALTER TABLE depot_notifications
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at DATETIME NULL,
    ADD COLUMN last_error_code VARCHAR(64) NULL,
    ADD COLUMN last_error_message VARCHAR(1000) NULL;
