package com.HomeRun.scheduler;

import com.OnETA.entity.ArrivalNotification;
import com.OnETA.entity.NotificationScheduleType;
import com.OnETA.repository.ArrivalNotificationRepository;
import com.OnETA.service.NotificationDeliveryService;
import com.OnETA.service.RepeatDaysService;
import com.OnETA.service.TransitApiService;
import com.OnETA.service.TransitScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Collections;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "app.scheduler.notification.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class NotificationScheduler {

    private static final Duration MAX_CANDIDATE_DELAY = Duration.ofMinutes(1);

    private final ArrivalNotificationRepository arrivalNotificationRepository;
    private final TransitApiService transitApiService;
    private final RepeatDaysService repeatDaysService;
    private final NotificationDeliveryService notificationDeliveryService;
    private final TransitScheduleService transitScheduleService;

    @Value("${app.time-zone:Asia/Seoul}")
    private String timeZone;

    // A replaceable clock keeps candidate-date behavior deterministic in tests.
    private Clock clock = Clock.systemUTC();

    public NotificationScheduler(ArrivalNotificationRepository notifications,
                                 TransitApiService transitApiService,
                                 RepeatDaysService repeatDaysService,
                                 NotificationDeliveryService delivery) {
        this(notifications, transitApiService, repeatDaysService, delivery, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationScheduler(ArrivalNotificationRepository notifications,
                                 TransitApiService transitApiService,
                                 RepeatDaysService repeatDaysService,
                                 NotificationDeliveryService delivery,
                                 TransitScheduleService transitScheduleService) {
        this.arrivalNotificationRepository = notifications;
        this.transitApiService = transitApiService;
        this.repeatDaysService = repeatDaysService;
        this.notificationDeliveryService = delivery;
        this.transitScheduleService = transitScheduleService;
    }

    @Scheduled(cron = "0 * * * * *", zone = "${app.time-zone:Asia/Seoul}")
    public void scheduleArrivalNotifications() {
        log.info("Executing arrival notification scheduler...");

        // TODO: Replace this full scan with findActiveCandidates(now) when the notification window/query is introduced.
        List<ArrivalNotification> activeNotifications =
                arrivalNotificationRepository.findAllByIsActiveTrue();
        ZoneId zoneId = ZoneId.of(timeZone);
        LocalDateTime now = ZonedDateTime.now(clock.withZone(zoneId)).toLocalDateTime();
        LocalDate today = now.toLocalDate();

        for (ArrivalNotification notification : activeNotifications) {
            boolean oneTime = notification.getRepeatDays() == null || notification.getRepeatDays() == 0;
            if (today.equals(notification.getLastSentDate())) continue;
            if (!isTodayCandidate(notification, now, oneTime)) continue;

            try {
                if (notification.getScheduleType() != null
                        && notification.getScheduleType() != NotificationScheduleType.NORMAL
                        && transitScheduleService != null) {
                    processScheduledTransit(notification, today, now, zoneId);
                    continue;
                }
                int estimatedDuration =
                        transitApiService.getRealTimeDuration(notification.getRouteDetails());
                List<Integer> reminderOffsets = reminderOffsetsOf(notification);
                for (Integer reminderOffset : reminderOffsets) {
                    Candidate candidate = findNextCandidate(notification, estimatedDuration, reminderOffset, now, oneTime);
                    if (candidate == null) continue;

                // Realtime duration can move today's candidate to tomorrow. Do not send it today.
                if (candidate.notificationTime().toLocalDate().equals(today)
                        && isDueCandidate(candidate.notificationTime(), now)) {
                    LocalDateTime scheduledAt = candidate.notificationTime()
                            .atZone(zoneId)
                            .withZoneSameInstant(java.time.ZoneOffset.UTC)
                            .toLocalDateTime();
                    if (reminderOffsets.size() == 1) {
                        notificationDeliveryService.prepare(
                                notification, estimatedDuration, today, scheduledAt);
                    } else {
                        notificationDeliveryService.prepare(
                                notification, estimatedDuration, today, scheduledAt, reminderOffset);
                    }
                    }
                }
            } catch (Exception e) {
                // 오래된 레코드 하나가 다른 사용자의 알림 처리까지 중단시키면 안 된다.
                // TODO: NotificationSendHistory를 도입해 FCM 성공 여부를 DB 상태와 독립적으로 추적한다.
                log.error(
                        "Skipping invalid arrival notification. notificationId={}, reason={}",
                        notification.getId(),
                        e.getMessage());
            }
        }
        notificationDeliveryService.processPending();
    }

    private void processScheduledTransit(ArrivalNotification notification, LocalDate today,
                                         LocalDateTime now, ZoneId zoneId) {
        if (!repeatDaysService.includes(notification.getRepeatDays(), today.getDayOfWeek())
                && notification.getRepeatDays() != 0) return;
        TransitScheduleService.Decision decision = transitScheduleService.evaluate(notification, today, now, zoneId);
        if (decision == null || !decision.hardDeadlineAt().isAfter(now)
                || decision.scheduledAt().isAfter(now)) return;
        LocalDateTime scheduledUtc = toUtc(decision.scheduledAt(), zoneId);
        LocalDateTime deadlineUtc = toUtc(decision.hardDeadlineAt(), zoneId);
        int offset = decision.recovery()
                ? notification.getReminderOffsetMinutesList().stream().max(Integer::compareTo).orElse(0)
                : notification.getReminderOffsetMinutes();
        boolean created = notificationDeliveryService.prepare(notification, decision.estimatedDuration(), today, scheduledUtc,
                offset, deadlineUtc, decision.phase());
        if (created && decision.recovery()) {
            transitScheduleService.markRecoveryDeliveryCreated(notification, today);
        }
    }

    private LocalDateTime toUtc(LocalDateTime value, ZoneId zoneId) {
        return value.atZone(zoneId).withZoneSameInstant(java.time.ZoneOffset.UTC).toLocalDateTime();
    }

    /**
     * Cheap pre-filter before the realtime API call. The final candidate is
     * recalculated after the realtime duration is known.
     */
    private boolean isTodayCandidate(ArrivalNotification notification, LocalDateTime now, boolean oneTime) {
        if (!oneTime && !repeatDaysService.includes(
                notification.getRepeatDays(), now.toLocalDate().getDayOfWeek())) {
            return false;
        }

        if (notification.getScheduleType() != null && notification.getScheduleType() != NotificationScheduleType.NORMAL) {
            return true;
        }

        return reminderOffsetsOf(notification).stream().anyMatch(offset ->
                isCandidateInWindow(now.toLocalDate().atTime(notification.getTargetArrivalTime())
                        .minusMinutes(offset), now));
    }

    private List<Integer> reminderOffsetsOf(ArrivalNotification notification) {
        List<Integer> offsets = notification.getReminderOffsetMinutesList();
        if (offsets == null || offsets.isEmpty()) {
            return Collections.singletonList(notification.getReminderOffsetMinutes());
        }
        return offsets;
    }

    private Candidate findNextCandidate(ArrivalNotification notification, int estimatedDuration, int reminderOffset,
                                         LocalDateTime now, boolean oneTime) {
        // A one-time alert can use today or tomorrow. A repeating alert can use
        // today or the next occurrence of its selected weekday within one week.
        int maxDays = oneTime ? 1 : 7;
        for (int dayOffset = 0; dayOffset <= maxDays; dayOffset++) {
            LocalDate arrivalDate = now.toLocalDate().plusDays(dayOffset);
            if (!oneTime && !repeatDaysService.includes(
                    notification.getRepeatDays(), arrivalDate.getDayOfWeek())) {
                continue;
            }

            LocalDateTime notificationTime = arrivalDate
                    .atTime(notification.getTargetArrivalTime())
                    .minusMinutes((long) estimatedDuration + reminderOffset);
            if (isCandidateInWindow(notificationTime, now)) {
                return new Candidate(notificationTime);
            }
        }
        return null;
    }

    /**
     * A candidate may be found when it is due now, up to one minute late, or in the future.
     * The caller decides whether a found candidate is already due before sending it.
     */
    private boolean isCandidateInWindow(LocalDateTime candidateTime, LocalDateTime now) {
        return !candidateTime.isBefore(now.minus(MAX_CANDIDATE_DELAY));
    }

    private boolean isDueCandidate(LocalDateTime candidateTime, LocalDateTime now) {
        return !candidateTime.isAfter(now) && isCandidateInWindow(candidateTime, now);
    }

    private record Candidate(LocalDateTime notificationTime) {
    }
}
