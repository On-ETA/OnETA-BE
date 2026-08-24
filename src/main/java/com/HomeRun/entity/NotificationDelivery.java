package com.HomeRun.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;

@Entity
@Table(name = "notification_deliveries", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_delivery_offset", columnNames = {"notification_id", "delivery_date", "reminder_offset_minutes"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    @Column(name = "reminder_offset_minutes", nullable = false)
    private int reminderOffsetMinutes;

    @Column(name = "device_token", nullable = false)
    private String deviceToken;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationDeliveryStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "hard_deadline_at", nullable = false)
    private LocalDateTime hardDeadlineAt;

    public NotificationDelivery(Notification notification, LocalDate deliveryDate,
                                String deviceToken, String title, String body,
                                LocalDateTime scheduledAt,
                                LocalDateTime hardDeadlineAt) {
        if (scheduledAt == null || hardDeadlineAt == null) {
            throw new IllegalArgumentException("scheduledAt과 hardDeadlineAt은 필수입니다.");
        }
        this.notification = notification;
        this.deliveryDate = deliveryDate;
        this.reminderOffsetMinutes = notification.getReminderOffsetMinutes();
        this.deviceToken = deviceToken;
        this.title = title;
        this.body = body;
        this.status = NotificationDeliveryStatus.PENDING;
        this.scheduledAt = scheduledAt;
        this.hardDeadlineAt = hardDeadlineAt;
    }

    public NotificationDelivery(Notification notification, LocalDate deliveryDate,
                                int reminderOffsetMinutes, String deviceToken, String title, String body,
                                LocalDateTime scheduledAt, LocalDateTime hardDeadlineAt) {
        this(notification, deliveryDate, deviceToken, title, body, scheduledAt, hardDeadlineAt);
        this.reminderOffsetMinutes = reminderOffsetMinutes;
    }

    public void markSending(LocalDateTime attemptedAt) {
        this.status = NotificationDeliveryStatus.SENDING;
        this.attempts++;
        this.lastAttemptAt = attemptedAt;
    }

    public void markTransientFailure(LocalDateTime nextAttemptAt, String errorCode, String errorMessage) {
        this.status = NotificationDeliveryStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        recordError(errorCode, errorMessage);
    }

    public void markFailed(String errorCode, String errorMessage) {
        this.status = NotificationDeliveryStatus.FAILED;
        this.nextAttemptAt = null;
        recordError(errorCode, errorMessage);
    }

    public void markExpired(String errorCode, String errorMessage) {
        this.status = NotificationDeliveryStatus.EXPIRED;
        this.nextAttemptAt = null;
        recordError(errorCode, errorMessage);
    }

    public void markSent() {
        this.status = NotificationDeliveryStatus.SENT;
        this.nextAttemptAt = null;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
    }

    public void markSentAt(LocalDateTime sentAt) {
        markSent();
        this.sentAt = sentAt;
    }

    public Duration getDeliveryLatency() {
        if (scheduledAt == null || sentAt == null) return null;
        return Duration.between(scheduledAt, sentAt);
    }

    private void recordError(String errorCode, String errorMessage) {
        this.lastErrorCode = truncate(errorCode, 64);
        this.lastErrorMessage = truncate(errorMessage, 1000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
