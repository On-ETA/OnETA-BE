-- Delivery rows created before the durable retry/deadline contract cannot be
-- assigned a trustworthy scheduled time. They are disposable outbox data.
DELETE FROM notification_deliveries;

ALTER TABLE notification_deliveries
    MODIFY COLUMN scheduled_at DATETIME NOT NULL,
    MODIFY COLUMN hard_deadline_at DATETIME NOT NULL;
