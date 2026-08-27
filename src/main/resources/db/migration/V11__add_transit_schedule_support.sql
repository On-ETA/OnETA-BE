ALTER TABLE arrival_notifications ADD COLUMN schedule_type VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

CREATE TABLE notification_schedule_snapshots (
    id BIGINT NOT NULL AUTO_INCREMENT,
    notification_id BIGINT NOT NULL,
    service_date DATE NOT NULL,
    schedule_type VARCHAR(20) NOT NULL,
    route_hash VARCHAR(64) NOT NULL,
    base_departure_at DATETIME NOT NULL,
    base_scheduled_at DATETIME NOT NULL,
    effective_departure_at DATETIME NOT NULL,
    effective_scheduled_at DATETIME NOT NULL,
    realtime_evaluation_start_at DATETIME NULL,
    last_realtime_evaluated_at DATETIME NULL,
    evaluation_mode VARCHAR(16) NOT NULL,
    first_opportunity_deadline DATETIME NULL,
    recovery_status VARCHAR(20) NOT NULL,
    recovery_next_retry_at DATETIME NULL,
    recovery_evaluation_deadline DATETIME NULL,
    source VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    calculated_at DATETIME NOT NULL,
    estimated_duration_minutes INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_schedule_snapshot UNIQUE (notification_id, service_date, schedule_type, route_hash),
    CONSTRAINT fk_schedule_snapshot_notification
        FOREIGN KEY (notification_id) REFERENCES arrival_notifications(notification_id)
);

ALTER TABLE notification_deliveries ADD COLUMN delivery_phase VARCHAR(16) NOT NULL DEFAULT 'BASE';
ALTER TABLE notification_deliveries DROP INDEX uk_notification_delivery_offset,
    ADD CONSTRAINT uk_notification_delivery_phase UNIQUE (notification_id, delivery_date, reminder_offset_minutes, delivery_phase);
