package com.HomeRun.scheduler;

import com.HomeRun.entity.ArrivalNotification;
import com.HomeRun.repository.ArrivalNotificationRepository;
import com.HomeRun.service.NotificationDeliveryService;
import com.HomeRun.service.RepeatDaysService;
import com.HomeRun.service.TransitApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private static final Duration MAX_CANDIDATE_DELAY = Duration.ofMinutes(1);

    private final ArrivalNotificationRepository arrivalNotificationRepository;
    private final TransitApiService transitApiService;
    private final RepeatDaysService repeatDaysService;
    private final NotificationDeliveryService notificationDeliveryService;

    @Value("${app.time-zone:Asia/Seoul}")
    private String timeZone;

    // A replaceable clock keeps candidate-date behavior deterministic in tests.
    private Clock clock = Clock.systemUTC();

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
                int estimatedDuration =
                        transitApiService.getRealTimeDuration(notification.getRouteDetails());
                Candidate candidate = findNextCandidate(notification, estimatedDuration, now, oneTime);
                if (candidate == null) continue;

                // Realtime duration can move today's candidate to tomorrow. Do not send it today.
                if (candidate.notificationTime().toLocalDate().equals(today)
                        && isDueCandidate(candidate.notificationTime(), now)) {
                    LocalDateTime scheduledAt = candidate.notificationTime()
                            .atZone(zoneId)
                            .withZoneSameInstant(java.time.ZoneOffset.UTC)
                            .toLocalDateTime();
                    notificationDeliveryService.prepare(
                            notification, estimatedDuration, today, scheduledAt);
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

    /**
     * Cheap pre-filter before the realtime API call. The final candidate is
     * recalculated after the realtime duration is known.
     */
    private boolean isTodayCandidate(ArrivalNotification notification, LocalDateTime now, boolean oneTime) {
        if (!oneTime && !repeatDaysService.includes(
                notification.getRepeatDays(), now.toLocalDate().getDayOfWeek())) {
            return false;
        }

        LocalDateTime baseNotificationTime = now.toLocalDate()
                .atTime(notification.getTargetArrivalTime())
                .minusMinutes(notification.getReminderOffsetMinutes());
        return isCandidateInWindow(baseNotificationTime, now);
    }

    private Candidate findNextCandidate(ArrivalNotification notification, int estimatedDuration,
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
                    .minusMinutes((long) estimatedDuration + notification.getReminderOffsetMinutes());
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
