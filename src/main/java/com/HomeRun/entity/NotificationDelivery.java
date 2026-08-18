package com.HomeRun.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "notification_deliveries", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_delivery_date", columnNames = {"notification_id", "delivery_date"}))
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

    public NotificationDelivery(Notification notification, LocalDate deliveryDate,
                                 String deviceToken, String title, String body) {
        this.notification = notification;
        this.deliveryDate = deliveryDate;
        this.deviceToken = deviceToken;
        this.title = title;
        this.body = body;
        this.status = NotificationDeliveryStatus.PENDING;
    }

    public void markSending(LocalDateTime attemptedAt) {
        this.status = NotificationDeliveryStatus.SENDING;
        this.attempts++;
        this.lastAttemptAt = attemptedAt;
    }

    public void markPending() {
        this.status = NotificationDeliveryStatus.PENDING;
    }

    public void markSent() {
        this.status = NotificationDeliveryStatus.SENT;
    }
}
