package com.OnETA.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Runs only against an isolated throwaway MySQL schema supplied by CI. */
@EnabledIfEnvironmentVariable(named = "NOTIFICATION_MIGRATION_CI", matches = "true")
class NotificationRevisionMigrationTest {

    @Test
    void migrationFromV22KeepsLegacyDataDisabledAndPermitsOnlyNewRevisionDeliveries() throws Exception {
        String url = System.getenv("MIGRATION_DB_URL");
        String username = System.getenv("MIGRATION_DB_USERNAME");
        String password = System.getenv("MIGRATION_DB_PASSWORD");
        assertThat(url).startsWith("jdbc:mysql://127.0.0.1:");
        assertThat(url).contains("/oneta_revision_migration");

        Flyway oldVersion = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("22"))
                .load();
        oldVersion.migrate();
        assertThat(oldVersion.info().current().getVersion().getVersion()).isEqualTo("22");

        long userId;
        long notificationId;
        long sentDeliveryId;
        long pendingDeliveryId;
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO users (email, password, nickname, role) VALUES ('" +
                    UUID.randomUUID() + "@example.invalid', 'pw', 'tester', 'USER')");
            try (ResultSet rs = sql.executeQuery("SELECT MAX(id) FROM users")) {
                assertThat(rs.next()).isTrue();
                userId = rs.getLong(1);
            }
            sql.executeUpdate("INSERT INTO notifications (notification_type, user_id, name, repeat_days, is_active, last_sent_date) " +
                    "VALUES ('ARRIVAL', " + userId + ", 'legacy-disabled', 0, FALSE, '2026-10-28')");
            try (ResultSet rs = sql.executeQuery("SELECT MAX(notification_id) FROM notifications")) {
                assertThat(rs.next()).isTrue();
                notificationId = rs.getLong(1);
            }
            sql.executeUpdate("INSERT INTO arrival_notifications (notification_id, target_arrival_time, route_details) " +
                    "VALUES (" + notificationId + ", '15:00:00', '{}')");
            sql.executeUpdate("INSERT INTO notification_reminder_offsets " +
                    "(notification_id, reminder_offset_minutes, offset_order) VALUES (" + notificationId + ", 3, 0)");

            sql.executeUpdate(deliveryInsert(notificationId, 3, "SENT", "'2026-10-28 05:55:00'"));
            try (ResultSet rs = sql.executeQuery("SELECT MAX(id) FROM notification_deliveries")) {
                rs.next();
                sentDeliveryId = rs.getLong(1);
            }
            sql.executeUpdate(deliveryInsert(notificationId, 10, "PENDING", "NULL"));
            try (ResultSet rs = sql.executeQuery("SELECT MAX(id) FROM notification_deliveries")) {
                rs.next();
                pendingDeliveryId = rs.getLong(1);
            }
        }

        Flyway latest = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load();
        latest.migrate();
        latest.validate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("23");

        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement sql = connection.createStatement()) {
            try (ResultSet rs = sql.executeQuery("SELECT is_active, auto_completed, schedule_revision, last_sent_revision " +
                    "FROM notifications WHERE notification_id = " + notificationId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean("is_active")).isFalse();
                assertThat(rs.getBoolean("auto_completed")).isFalse();
                assertThat(rs.getLong("schedule_revision")).isZero();
                assertThat(rs.getLong("last_sent_revision")).isZero();
            }
            try (ResultSet rs = sql.executeQuery("SELECT status, sent_at, schedule_revision FROM notification_deliveries " +
                    "WHERE id = " + sentDeliveryId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("SENT");
                assertThat(rs.getTimestamp("sent_at")).isNotNull();
                assertThat(rs.getLong("schedule_revision")).isZero();
            }
            try (ResultSet rs = sql.executeQuery("SELECT status, schedule_revision FROM notification_deliveries " +
                    "WHERE id = " + pendingDeliveryId)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("PENDING");
                assertThat(rs.getLong("schedule_revision")).isZero();
            }

            sql.executeUpdate("UPDATE notifications SET schedule_revision = 1 WHERE notification_id = " + notificationId);
            sql.executeUpdate("INSERT INTO notification_deliveries " +
                    "(notification_id, delivery_date, device_token, title, body, status, attempts, " +
                    "scheduled_at, hard_deadline_at, reminder_offset_minutes, delivery_phase, schedule_revision) " +
                    "VALUES (" + notificationId + ", '2026-10-28', 'rotated-token', 'new', 'new', 'PENDING', 0, " +
                    "'2026-10-28 06:55:00', '2026-10-28 06:58:00', 3, 'BASE', 1)");
            assertThatThrownBy(() -> sql.executeUpdate("INSERT INTO notification_deliveries " +
                    "(notification_id, delivery_date, device_token, title, body, status, attempts, " +
                    "scheduled_at, hard_deadline_at, reminder_offset_minutes, delivery_phase, schedule_revision) " +
                    "VALUES (" + notificationId + ", '2026-10-28', 'rotated-token', 'duplicate', 'duplicate', 'PENDING', 0, " +
                    "'2026-10-28 06:55:00', '2026-10-28 06:58:00', 3, 'BASE', 1)"))
                    .isInstanceOf(SQLException.class);
            try (ResultSet rs = sql.executeQuery("SELECT COUNT(*) FROM notification_deliveries " +
                    "WHERE notification_id = " + notificationId)) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(3);
            }
        }
    }

    private static String deliveryInsert(long notificationId, int offset, String status, String sentAt) {
        return "INSERT INTO notification_deliveries " +
                "(notification_id, delivery_date, device_token, title, body, status, attempts, " +
                "scheduled_at, hard_deadline_at, reminder_offset_minutes, delivery_phase, sent_at) VALUES (" +
                notificationId + ", '2026-10-28', 'legacy-token-" + offset + "', 'old', 'old', '" + status +
                "', 1, '2026-10-28 05:55:00', '2026-10-28 06:05:00', " + offset + ", 'BASE', " + sentAt + ")";
    }
}
