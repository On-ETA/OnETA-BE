-- ONE-TIME reconciliation for localhost:3306/homerun ONLY.
-- Stop the application and export a backup before running this entire file
-- in MySQL Workbench (stop on errors). Do not run against production.
-- Based on the schema and history supplied on 2026-09-09.
-- V1 was already baselined. V2-V13 effects were partially applied outside
-- Flyway. Preserve business rows and archive history instead of inventing
-- successful migration records. V14 and V15 still run normally afterwards.
USE homerun;

DELIMITER $$
CREATE PROCEDURE reconcile_local_flyway_20260909()
BEGIN
    IF DATABASE() <> 'homerun' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Wrong database';
    END IF;

    IF (SELECT COUNT(*) FROM flyway_schema_history) <> 5
       OR (SELECT COUNT(*) FROM flyway_schema_history
           WHERE success = 1 AND (
               (version = '1' AND type = 'BASELINE')
               OR (version = '2' AND checksum = 2136040493
                   AND script = 'V2__convert_repeat_days_to_bitmask.sql')
               OR (version = '3' AND checksum = -1882780720
                   AND script = 'V3__remove_scheduled_date.sql')
               OR (version = '4' AND checksum = 21904925
                   AND script = 'V4__create_user_addresses.sql')
               OR (version = '5' AND checksum = 1720331106
                   AND script = 'V5__add_address_text_to_user_addresses.sql')
           )) <> 5 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'History differs from reviewed history; stop';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = DATABASE()
                 AND table_name IN ('flyway_schema_history_before_reconcile_20260909',
                                    'flyway_schema_history_reconciled_20260909')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Reconciliation tables already exist; stop and inspect';
    END IF;

    -- Guard the specific schema changes being adopted in this baseline.
    IF (SELECT COUNT(*) FROM information_schema.columns
        WHERE table_schema = DATABASE() AND (
            (table_name = 'notifications' AND column_name IN
                ('repeat_days', 'is_active', 'last_sent_date', 'reminder_offset_minutes'))
            OR (table_name = 'notification_reminder_offsets' AND column_name IN
                ('notification_id', 'reminder_offset_minutes', 'offset_order'))
            OR (table_name = 'notification_deliveries' AND column_name IN
                ('next_attempt_at', 'last_error_code', 'last_error_message',
                 'scheduled_at', 'sent_at', 'hard_deadline_at',
                 'reminder_offset_minutes', 'delivery_phase'))
            OR (table_name = 'arrival_notifications' AND column_name = 'schedule_type')
            OR (table_name = 'user_addresses' AND column_name = 'address')
            OR (table_name = 'notification_schedule_snapshots' AND column_name IN
                ('id', 'notification_id', 'service_date', 'schedule_type', 'route_hash',
                 'base_departure_at', 'base_scheduled_at', 'effective_departure_at',
                 'effective_scheduled_at', 'realtime_evaluation_start_at',
                 'last_realtime_evaluated_at', 'evaluation_mode',
                 'first_opportunity_deadline', 'recovery_status', 'recovery_next_retry_at',
                 'recovery_evaluation_deadline', 'source', 'status', 'calculated_at',
                 'estimated_duration_minutes'))
        )) <> 37 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Schema differs from reviewed schema; stop';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = 'notifications'
                 AND column_name = 'scheduled_date') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unexpected legacy scheduled_date column; stop';
    END IF;

    IF EXISTS (SELECT 1 FROM notification_reminder_offsets
               WHERE reminder_offset_minutes NOT IN (1, 3, 5, 10, 15, 30, 60))
       OR EXISTS (SELECT 1 FROM notifications n
                  WHERE NOT EXISTS (SELECT 1 FROM notification_reminder_offsets o
                                    WHERE o.notification_id = n.notification_id)
                    AND n.reminder_offset_minutes NOT IN (1, 3, 5, 10, 15, 30, 60)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unsupported reminder offset; resolve values before retrying';
    END IF;

    IF EXISTS (SELECT 1 FROM notifications WHERE repeat_days < 0 OR repeat_days > 127)
       OR EXISTS (SELECT 1 FROM notification_deliveries
                  WHERE scheduled_at IS NULL OR hard_deadline_at IS NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Invalid repeat mask or missing delivery deadline; stop';
    END IF;

    -- Copy only where no list exists; keep existing multiple reminder choices.
    -- Retain the obsolete scalar column and its values for data preservation.
    INSERT INTO notification_reminder_offsets
        (notification_id, reminder_offset_minutes, offset_order)
    SELECT n.notification_id, n.reminder_offset_minutes, 0
    FROM notifications n
    WHERE NOT EXISTS (SELECT 1 FROM notification_reminder_offsets o
                      WHERE o.notification_id = n.notification_id);

    -- JPA no longer writes this scalar column, so new inserts need a default.
    ALTER TABLE notifications ALTER COLUMN reminder_offset_minutes SET DEFAULT 0;

    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
                   WHERE constraint_schema = DATABASE()
                     AND table_name = 'notification_reminder_offsets'
                     AND constraint_name = 'chk_notification_reminder_offset_value') THEN
        ALTER TABLE notification_reminder_offsets
            ADD CONSTRAINT chk_notification_reminder_offset_value
            CHECK (reminder_offset_minutes IN (1, 3, 5, 10, 15, 30, 60));
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                   WHERE table_schema = DATABASE()
                     AND table_name = 'notification_deliveries'
                     AND index_name = 'idx_notification_delivery_retry') THEN
        CREATE INDEX idx_notification_delivery_retry
            ON notification_deliveries (status, next_attempt_at, id);
    END IF;

    -- Use a genuine BASELINE record, not fake V2-V13 execution records.
    CREATE TABLE flyway_schema_history_reconciled_20260909 LIKE flyway_schema_history;
    INSERT INTO flyway_schema_history_reconciled_20260909
        (installed_rank, version, description, type, script, checksum,
         installed_by, installed_on, execution_time, success)
    VALUES (1, '13', 'Reconciled local schema through V13', 'BASELINE',
            'Reconciled local schema through V13', NULL, CURRENT_USER(), CURRENT_TIMESTAMP, 0, 1);

    -- Atomic name swap; preserve the complete original history.
    RENAME TABLE
        flyway_schema_history TO flyway_schema_history_before_reconcile_20260909,
        flyway_schema_history_reconciled_20260909 TO flyway_schema_history;
END$$
DELIMITER ;

CALL reconcile_local_flyway_20260909();
DROP PROCEDURE reconcile_local_flyway_20260909;

SELECT installed_rank, version, description, type, success
FROM flyway_schema_history ORDER BY installed_rank;

-- Restart with the local profile. Flyway should apply V14 and V15.
-- Afterwards verify:
-- SELECT version, description, success FROM homerun.flyway_schema_history;
-- SHOW CREATE TABLE homerun.faqs;
