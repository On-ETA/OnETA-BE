CREATE TABLE notification_reminder_offsets (
    notification_id BIGINT NOT NULL,
    reminder_offset_minutes INT NOT NULL,
    offset_order INT NOT NULL,
    PRIMARY KEY (notification_id, offset_order),
    CONSTRAINT fk_notification_reminder_offset_notification
        FOREIGN KEY (notification_id) REFERENCES notifications (notification_id),
    CONSTRAINT chk_notification_reminder_offset_value
        CHECK (reminder_offset_minutes IN (1, 3, 5, 10, 15, 30, 60))
);

INSERT INTO notification_reminder_offsets (notification_id, reminder_offset_minutes, offset_order)
SELECT notification_id, reminder_offset_minutes, 0
FROM notifications;

ALTER TABLE notifications DROP COLUMN reminder_offset_minutes;

ALTER TABLE notification_deliveries
    ADD COLUMN reminder_offset_minutes INT NOT NULL DEFAULT 0;

ALTER TABLE notification_deliveries
    DROP INDEX uk_notification_delivery_date,
    ADD CONSTRAINT uk_notification_delivery_offset
        UNIQUE (notification_id, delivery_date, reminder_offset_minutes);
