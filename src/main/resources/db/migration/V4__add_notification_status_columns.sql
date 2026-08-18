-- Add the columns mapped by Notification.
-- Existing notifications are active unless they have been explicitly completed.
ALTER TABLE notifications
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN last_sent_date DATE NULL;
