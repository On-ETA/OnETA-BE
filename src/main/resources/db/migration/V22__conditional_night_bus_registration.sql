-- Conditional N-bus registration is never treated as a confirmed stop timetable.
-- Deliveries for these schedules require a matching live vehicle prediction.
ALTER TABLE arrival_notifications
    ADD COLUMN conditional_night_bus BOOLEAN NOT NULL DEFAULT FALSE;
