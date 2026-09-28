UPDATE notifications
SET repeat_days = 0
WHERE notification_id IN (
    SELECT notification_id FROM arrival_notifications
    WHERE schedule_type IN ('FIRST_TRANSIT', 'LAST_TRANSIT')
);
