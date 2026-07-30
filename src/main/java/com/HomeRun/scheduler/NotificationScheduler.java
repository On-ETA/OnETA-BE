package com.HomeRun.scheduler;

import com.HomeRun.entity.ArrivalNotification;
import com.HomeRun.repository.ArrivalNotificationRepository;
import com.HomeRun.repository.UserDeviceTokenRepository;
import com.HomeRun.service.FcmPushService;
import com.HomeRun.service.TransitApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final ArrivalNotificationRepository arrivalNotificationRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final TransitApiService transitApiService;
    private final FcmPushService fcmPushService;

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void scheduleArrivalNotifications() {
        log.info("Executing arrival notification scheduler...");

        List<ArrivalNotification> activeNotifications =
                arrivalNotificationRepository.findAllByIsActiveTrue();
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        String currentDayOfWeek =
                today.getDayOfWeek().name().substring(0, 3).toUpperCase();

        for (ArrivalNotification notification : activeNotifications) {
            if (notification.getRepeatDays() == null
                    || !notification.getRepeatDays().contains(currentDayOfWeek)
                    || today.equals(notification.getLastSentDate())) {
                continue;
            }

            try {
                int estimatedDuration =
                        transitApiService.getRealTimeDuration(notification.getRouteDetails());
                LocalTime expectedDepartureTime =
                        notification.getTargetArrivalTime().minusMinutes(estimatedDuration);
                LocalTime notificationTime = expectedDepartureTime
                        .minusMinutes(notification.getReminderOffsetMinutes());

                if (!now.isBefore(notificationTime)) {
                    sendPushAndUpdateStatus(notification, estimatedDuration);
                }
            } catch (Exception e) {
                // 오래된 레코드 하나가 다른 사용자의 알림 처리까지 중단시키면 안 된다.
                log.error(
                        "Skipping invalid arrival notification. notificationId={}, reason={}",
                        notification.getId(),
                        e.getMessage());
            }
        }
    }

    private void sendPushAndUpdateStatus(
            ArrivalNotification notification, int estimatedDuration) {
        userDeviceTokenRepository.findByUserId(notification.getUser().getId())
                .ifPresent(token -> {
                    String title = "출발 알림: " + notification.getName();
                    String body = String.format(
                            "지금 출발하시면 목표 시간(%s)에 도착할 수 있습니다. (예상 소요 시간: %d분)",
                            notification.getTargetArrivalTime(),
                            estimatedDuration);

                    fcmPushService.sendPushMessage(token.getDeviceToken(), title, body);
                    notification.updateLastSentDate(LocalDate.now());
                    arrivalNotificationRepository.save(notification);
                });
    }
}
