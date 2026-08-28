-- Initial schema immediately before V2 converts notifications.repeat_days
-- from weekday text to the integer bit mask used by the current entity.

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    password VARCHAR(255) NULL,
    nickname VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE email_verifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    verification_code VARCHAR(255) NOT NULL,
    expiration_time DATETIME NOT NULL,
    verified BOOLEAN NOT NULL,
    send_count INT NOT NULL,
    last_sent_at DATETIME NULL,
    attempt_count INT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_email_verifications_email UNIQUE (email)
);

CREATE TABLE refresh_tokens (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL,
    token VARCHAR(512) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_email UNIQUE (email)
);

CREATE TABLE notices (
    id BIGINT NOT NULL AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    author VARCHAR(255) NOT NULL,
    view_count INT NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE user_device_tokens (
    token_id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    device_token VARCHAR(255) NOT NULL,
    PRIMARY KEY (token_id),
    CONSTRAINT uk_user_device_tokens_device_token UNIQUE (device_token),
    CONSTRAINT fk_user_device_tokens_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE notifications (
    notification_id BIGINT NOT NULL AUTO_INCREMENT,
    notification_type VARCHAR(31) NOT NULL,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    reminder_offset_minutes INT NOT NULL,
    repeat_days VARCHAR(255) NOT NULL,
    PRIMARY KEY (notification_id),
    CONSTRAINT fk_notifications_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);

-- JOINED inheritance uses the child PK as an FK to notifications.
CREATE TABLE arrival_notifications (
    notification_id BIGINT NOT NULL,
    target_arrival_time TIME NOT NULL,
    route_details TEXT NULL,
    first_station_id VARCHAR(255) NULL,
    first_route_id VARCHAR(255) NULL,
    target_boarding_time TIME NULL,
    PRIMARY KEY (notification_id),
    CONSTRAINT fk_arrival_notifications_notification
        FOREIGN KEY (notification_id) REFERENCES notifications (notification_id)
);
