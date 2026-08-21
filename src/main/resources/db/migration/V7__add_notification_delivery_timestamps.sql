ALTER TABLE notification_deliveries
    ADD COLUMN scheduled_at DATETIME NULL,
    ADD COLUMN sent_at DATETIME NULL;
