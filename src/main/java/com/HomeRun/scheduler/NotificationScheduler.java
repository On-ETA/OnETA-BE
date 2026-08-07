package com.HomeRun.scheduler;

import com.HomeRun.entity.ArrivalNotification;
import com.HomeRun.repository.ArrivalNotificationRepository;
import com.HomeRun.repository.UserDeviceTokenRepository;
import com.HomeRun.service.FcmPushService;
import com.HomeRun.service.RepeatDaysService;
import com.HomeRun.service.TransitApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final ArrivalNotificationRepository arrivalNotificationRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final TransitApiService transitApiService;
    private final FcmPushService fcmPushService;
    private final RepeatDaysService repeatDaysService;

    @Value("${app.time-zone:Asia/Seoul}")
    private String timeZone;

    // A replaceable clock keeps candidate-date behavior deterministic in tests.
    private Clock clock = Clock.systemUTC();

    @Scheduled(cron = "0 * * * * *")
    @Transactional
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
                        && !now.isBefore(candidate.notificationTime())) {
                    sendPushAndUpdateStatus(notification, estimatedDuration, oneTime, today);
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
        return !baseNotificationTime.isBefore(now);
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
            if (!notificationTime.isBefore(now)) {
                return new Candidate(notificationTime);
            }
        }
        return null;
    }

    private void sendPushAndUpdateStatus(
            ArrivalNotification notification, int estimatedDuration, boolean oneTime, LocalDate today) {
        userDeviceTokenRepository.findByUserId(notification.getUser().getId())
                .ifPresent(token -> {
                    String title = "출발 알림: " + notification.getName();
                    String body = String.format(
                            "지금 출발하시면 목표 시간(%s)에 도착할 수 있습니다. (예상 소요 시간: %d분)",
                            notification.getTargetArrivalTime(),
                            estimatedDuration);

                    fcmPushService.sendPushMessage(token.getDeviceToken(), title, body);
                    notification.updateLastSentDate(today);
                    if (oneTime) notification.completeOneTimeNotification();
                    arrivalNotificationRepository.save(notification);
                });
    }

    private record Candidate(LocalDateTime notificationTime) {
    }
}
