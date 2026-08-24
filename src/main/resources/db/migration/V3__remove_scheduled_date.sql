-- scheduled_date was part of an intermediate implementation and is not used.
SET @drop_scheduled_date = IF(
    EXISTS(
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'notifications'
          AND column_name = 'scheduled_date'
    ),
    'ALTER TABLE notifications DROP COLUMN scheduled_date',
    'SELECT 1'
);

PREPARE drop_scheduled_date_statement FROM @drop_scheduled_date;
EXECUTE drop_scheduled_date_statement;
DEALLOCATE PREPARE drop_scheduled_date_statement;
