-- Existing repeat_days values are expected to be comma-separated weekday names,
-- for example: MON,TUE,WED or MON, WED, FRI.
-- Run this migration once before changing the JPA schema from VARCHAR to INT.

ALTER TABLE notifications ADD COLUMN repeat_days_mask INT NULL;

-- Reject unknown or malformed weekday tokens before converting them.
-- The expression uses the same uppercase/space normalization as the UPDATE below.
ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_repeat_days_tokens CHECK (
        REPLACE(UPPER(COALESCE(repeat_days, '')), ' ', '') = ''
        OR REPLACE(UPPER(COALESCE(repeat_days, '')), ' ', '') REGEXP
           '^(MON|TUE|WED|THU|FRI|SAT|SUN)(,(MON|TUE|WED|THU|FRI|SAT|SUN))*$'
    );

UPDATE notifications
SET repeat_days_mask =
      IF(FIND_IN_SET('MON', REPLACE(UPPER(COALESCE(repeat_days, '')), ' ', '')) > 0, 1, 0)
    + IF(FIND_IN_SET('TUE', REPLACE(UPPER(COALESCE(repeat_days, '')), ' ', '')) > 0, 2, 0)
    + IF(FIND_IN_SET('WED', REPLACE(UPPER(COALESCE(repeat_days, '')), ' ', '')) > 0, 4, 0)
    + IF(FIND_IN_SET('THU', REPLACE(UPPER(COALESCE(repeat_days, '')), ' ', '')) > 0, 8, 0)
    + IF(FIND_IN_SET('FRI', REPLACE(UPPER(COALESCE(repeat_days, '')), ' ', '')) > 0, 16, 0)
    + IF(FIND_IN_SET('SAT', REPLACE(UPPER(COALESCE(repeat_days, '')), ' ', '')) > 0, 32, 0)
    + IF(FIND_IN_SET('SUN', REPLACE(UPPER(COALESCE(repeat_days, '')), ' ', '')) > 0, 64, 0);

ALTER TABLE notifications DROP CHECK chk_notifications_repeat_days_tokens;
ALTER TABLE notifications DROP COLUMN repeat_days;
ALTER TABLE notifications CHANGE COLUMN repeat_days_mask repeat_days INT NOT NULL DEFAULT 0;
