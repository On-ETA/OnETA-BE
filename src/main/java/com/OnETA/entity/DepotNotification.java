package com.OnETA.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import com.OnETA.entity.NotificationDeliveryStatus;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "depot_notifications")
public class DepotNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 등록된 버스 중 어떤 버스에 대한 알림인지 연결 (1:1 관계)
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_bus_id", nullable = false)
    private UserBus userBus;

    @Column(nullable = false)
    private boolean active; // 알림 활성화 여부

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationDeliveryStatus status = NotificationDeliveryStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Builder
    public DepotNotification(UserBus userBus, boolean active) {
        this.userBus = userBus;
        this.active = active;
    }

    // 알림 켜기
    public void enableNotification() {
        this.active = true;
        this.status = NotificationDeliveryStatus.PENDING;
        this.attempts = 0;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
    }

    // 알림 끄기 (1회 발송 후 자동 호출)
    public void disableNotification() {
        this.active = false;
    }

    public void markSending(LocalDateTime attemptedAt) {
        this.status = NotificationDeliveryStatus.SENDING;
        this.attempts++;
        this.lastAttemptAt = attemptedAt;
    }

    public void markSent() {
        this.status = NotificationDeliveryStatus.SENT;
        this.lastErrorCode = null;
        this.lastErrorMessage = null;
    }

    public void markFailed(String errorCode, String errorMessage) {
        this.status = NotificationDeliveryStatus.FAILED;
        this.lastErrorCode = truncate(errorCode, 64);
        this.lastErrorMessage = truncate(errorMessage, 1000);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}