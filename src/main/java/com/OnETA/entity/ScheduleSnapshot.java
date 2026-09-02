package com.OnETA.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "notification_schedule_snapshots", uniqueConstraints = @UniqueConstraint(
        name = "uk_schedule_snapshot", columnNames = {"notification_id", "service_date", "schedule_type", "route_hash"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduleSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private ArrivalNotification notification;
    @Column(name = "service_date", nullable = false) private LocalDate serviceDate;
    @Enumerated(EnumType.STRING) @Column(name = "schedule_type", nullable = false, length = 20)
    private NotificationScheduleType scheduleType;
    @Column(name = "route_hash", nullable = false, length = 64) private String routeHash;
    @Column(name = "base_departure_at", nullable = false) private LocalDateTime baseDepartureAt;
    @Column(name = "base_scheduled_at", nullable = false) private LocalDateTime baseScheduledAt;
    @Column(name = "effective_departure_at", nullable = false) private LocalDateTime effectiveDepartureAt;
    @Column(name = "effective_scheduled_at", nullable = false) private LocalDateTime effectiveScheduledAt;
    @Column(name = "realtime_evaluation_start_at") private LocalDateTime realtimeEvaluationStartAt;
    @Column(name = "last_realtime_evaluated_at") private LocalDateTime lastRealtimeEvaluatedAt;
    @Enumerated(EnumType.STRING) @Column(name = "evaluation_mode", nullable = false, length = 16)
    private ScheduleEvaluationMode evaluationMode;
    @Column(name = "first_opportunity_deadline") private LocalDateTime firstOpportunityDeadline;
    @Enumerated(EnumType.STRING) @Column(name = "recovery_status", nullable = false, length = 20)
    private RecoveryStatus recoveryStatus;
    @Column(name = "recovery_next_retry_at") private LocalDateTime recoveryNextRetryAt;
    @Column(name = "recovery_evaluation_deadline") private LocalDateTime recoveryEvaluationDeadline;
    @Column(name = "source", nullable = false, length = 32) private String source;
    @Column(name = "status", nullable = false, length = 32) private String status;
    @Column(name = "calculated_at", nullable = false) private LocalDateTime calculatedAt;
    @Column(name = "estimated_duration_minutes", nullable = false) private int estimatedDurationMinutes;

    public ScheduleSnapshot(ArrivalNotification notification, LocalDate serviceDate,
                            NotificationScheduleType scheduleType, String routeHash,
                            LocalDateTime departure, LocalDateTime scheduled,
                            LocalDateTime evaluationStart, LocalDateTime calculatedAt, int estimatedDurationMinutes) {
        this.notification = notification; this.serviceDate = serviceDate; this.scheduleType = scheduleType;
        this.routeHash = routeHash; this.baseDepartureAt = departure; this.baseScheduledAt = scheduled;
        this.effectiveDepartureAt = departure; this.effectiveScheduledAt = scheduled;
        this.realtimeEvaluationStartAt = evaluationStart; this.evaluationMode = ScheduleEvaluationMode.BASE;
        this.firstOpportunityDeadline = departure; this.recoveryStatus = RecoveryStatus.NOT_ATTEMPTED;
        this.source = "ODSAY"; this.status = "SUCCESS"; this.calculatedAt = calculatedAt;
        this.estimatedDurationMinutes = estimatedDurationMinutes;
    }

    public void markRealtime(LocalDateTime departure, LocalDateTime scheduled, LocalDateTime evaluatedAt) {
        if (scheduled.isBefore(this.effectiveScheduledAt)) {
            this.effectiveScheduledAt = scheduled; this.effectiveDepartureAt = departure;
        }
        this.lastRealtimeEvaluatedAt = evaluatedAt;
    }
    public void startRecovery(LocalDateTime retryAt) {
        this.evaluationMode = ScheduleEvaluationMode.RECOVERY;
        this.recoveryStatus = RecoveryStatus.EVALUATING;
        this.recoveryNextRetryAt = retryAt;
        if (this.recoveryEvaluationDeadline == null) this.recoveryEvaluationDeadline = retryAt.plusMinutes(5);
    }
    public LocalDateTime getRecoveryEvaluationDeadline() { return recoveryEvaluationDeadline; }
    public void markRecoveryRetry(LocalDateTime retryAt) {
        this.evaluationMode = ScheduleEvaluationMode.RECOVERY;
        this.recoveryStatus = RecoveryStatus.EVALUATING;
        this.recoveryNextRetryAt = retryAt;
    }
    public void markRecovery(RecoveryStatus status) { this.recoveryStatus = status; this.recoveryNextRetryAt = null; }
    public void markRecoveryDeliveryCreated() { this.evaluationMode = ScheduleEvaluationMode.RECOVERY; this.recoveryStatus = RecoveryStatus.DELIVERY_CREATED; this.recoveryNextRetryAt = null; }
    public void finish() { this.evaluationMode = ScheduleEvaluationMode.FINISHED; this.recoveryStatus = RecoveryStatus.FINISHED; }
}
