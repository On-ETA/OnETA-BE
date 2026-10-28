-- Preserve historical deliveries while allowing a changed schedule to send again on the same date.
ALTER TABLE notifications
    ADD COLUMN schedule_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_sent_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN auto_completed BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing disabled schedules may have been turned off manually. Their history cannot
-- distinguish user intent from one-time auto-completion, so leave auto_completed FALSE.
-- Only completions after this migration are eligible for automatic reactivation.

ALTER TABLE notification_deliveries
    ADD COLUMN schedule_revision BIGINT NOT NULL DEFAULT 0,
    DROP INDEX uk_notification_delivery_phase,
    ADD CONSTRAINT uk_notification_delivery_revision
        UNIQUE (notification_id, delivery_date, reminder_offset_minutes, delivery_phase, schedule_revision);
