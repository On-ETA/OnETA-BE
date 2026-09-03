-- seoul_bus_route 테이블
CREATE TABLE IF NOT EXISTS seoul_bus_route (
    route_id VARCHAR(255) PRIMARY KEY,
    route_nm VARCHAR(255) NOT NULL,
    start_point VARCHAR(255),
    end_point VARCHAR(255),
    term VARCHAR(255),
    turnaround_seq INT
    );

-- user_buses 테이블 (users 테이블과 연관관계)
CREATE TABLE IF NOT EXISTS user_buses (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    route_id VARCHAR(255) NOT NULL,
    bus_number VARCHAR(255) NOT NULL,
    direction VARCHAR(50) NOT NULL,
    direction_name VARCHAR(255) NOT NULL,
    CONSTRAINT fk_user_buses_user_id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
    );

-- depot_notifications 테이블 (user_buses 테이블과 1:1 연관관계)
CREATE TABLE IF NOT EXISTS depot_notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_bus_id BIGINT NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    CONSTRAINT fk_depot_notifications_user_bus_id FOREIGN KEY (user_bus_id) REFERENCES user_buses (id) ON DELETE CASCADE,
    CONSTRAINT uk_depot_notifications_user_bus_id UNIQUE (user_bus_id)
    );