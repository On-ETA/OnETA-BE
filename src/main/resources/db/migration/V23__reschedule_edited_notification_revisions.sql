-- Preserve historical deliveries while allowing a changed schedule to send again on the same date.
ALTER TABLE notifications
    ADD COLUMN schedule_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN last_sent_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN auto_completed BOOLEAN NOT NULL DEFAULT FALSE;

-- Recover completed one-time reminders created before completion tracking existed.
-- Only records with a successful delivery can be classified as historically completed.
UPDATE notifications n
SET n.auto_completed = TRUE
WHERE n.is_active = FALSE
  AND n.repeat_days = 0
  AND n.last_sent_date IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM notification_deliveries d
      WHERE d.notification_id = n.notification_id
        AND d.delivery_date = n.last_sent_date
        AND d.status = 'SENT'
  );

ALTER TABLE notification_deliveries
    ADD COLUMN schedule_revision BIGINT NOT NULL DEFAULT 0,
    DROP INDEX uk_notification_delivery_phase,
    ADD CONSTRAINT uk_notification_delivery_revision
        UNIQUE (notification_id, delivery_date, reminder_offset_minutes, delivery_phase, schedule_revision);
