ALTER TABLE arrival_notifications MODIFY COLUMN target_arrival_time TIME NULL;

UPDATE arrival_notifications
SET target_arrival_time = NULL
WHERE schedule_type IN ('FIRST_TRANSIT', 'LAST_TRANSIT');
