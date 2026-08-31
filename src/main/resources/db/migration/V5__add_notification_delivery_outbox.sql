CREATE TABLE notification_deliveries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    notification_id BIGINT NOT NULL,
    delivery_date DATE NOT NULL,
    device_token VARCHAR(255) NOT NULL,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_attempt_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_notification_delivery_date UNIQUE (notification_id, delivery_date),
    CONSTRAINT fk_notification_delivery_notification
        FOREIGN KEY (notification_id) REFERENCES notifications (notification_id)
);
