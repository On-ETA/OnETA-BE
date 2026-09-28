-- Keep notification names and all related rows: NORMAL schedules still require notifications.name.
ALTER TABLE arrival_notifications
    ADD COLUMN transit_archived BOOLEAN NOT NULL DEFAULT FALSE;

-- Prefer the newest active first/last notification; if none is active, keep the newest one.
-- The grouped derived table is materialized by MySQL (no target-table self-update ambiguity).
UPDATE arrival_notifications a
JOIN notifications n ON n.notification_id = a.notification_id
JOIN (
    SELECT n0.user_id,
           COALESCE(MAX(CASE WHEN n0.is_active = TRUE THEN n0.notification_id END),
                    MAX(n0.notification_id)) AS keep_id
    FROM notifications n0
    JOIN arrival_notifications a0 ON a0.notification_id = n0.notification_id
    WHERE a0.schedule_type IN ('FIRST_TRANSIT', 'LAST_TRANSIT')
    GROUP BY n0.user_id
) keepers ON keepers.user_id = n.user_id
SET a.transit_archived = TRUE
WHERE a.schedule_type IN ('FIRST_TRANSIT', 'LAST_TRANSIT')
  AND a.notification_id <> keepers.keep_id;

UPDATE notifications n
JOIN arrival_notifications a ON a.notification_id = n.notification_id
SET n.is_active = FALSE
WHERE a.transit_archived = TRUE;

UPDATE notification_deliveries d
JOIN arrival_notifications a ON a.notification_id = d.notification_id
SET d.status = 'EXPIRED', d.next_attempt_at = NULL,
    d.last_error_code = 'TRANSIT_REPLACED',
    d.last_error_message = 'Archived duplicate first/last transit notification'
WHERE a.transit_archived = TRUE AND d.status IN ('PENDING', 'SENDING');
