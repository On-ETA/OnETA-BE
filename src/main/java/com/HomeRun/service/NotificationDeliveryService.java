package com.HomeRun.service;

import com.HomeRun.entity.ArrivalNotification;
import com.HomeRun.entity.NotificationDelivery;
import com.HomeRun.entity.NotificationDeliveryStatus;
import com.HomeRun.repository.ArrivalNotificationRepository;
import com.HomeRun.repository.NotificationDeliveryRepository;
import com.HomeRun.repository.UserDeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryService {

    private static final Duration SENDING_RETRY_DELAY = Duration.ofMinutes(5);

    private final NotificationDeliveryRepository deliveryRepository;
    private final ArrivalNotificationRepository arrivalNotificationRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final FcmPushService fcmPushService;
    private final PlatformTransactionManager transactionManager;

    /** Outbox and notification state are committed before FCM is called. */
    @Transactional
    public void prepare(ArrivalNotification notification, int estimatedDuration,
                        boolean oneTime, LocalDate deliveryDate) {
        if (deliveryRepository.findByNotificationIdAndDeliveryDate(
                notification.getId(), deliveryDate).isPresent()) return;

        userDeviceTokenRepository.findByUserId(notification.getUser().getId()).ifPresent(token -> {
            String title = "출발 알림: " + notification.getName();
            String body = String.format(
                    "지금 출발하시면 목표 시간(%s)에 도착할 수 있습니다. (예상 소요 시간: %d분)",
                    notification.getTargetArrivalTime(), estimatedDuration);
            deliveryRepository.save(new NotificationDelivery(
                    notification, deliveryDate, token.getDeviceToken(), title, body));
            notification.updateLastSentDate(deliveryDate);
            if (oneTime) notification.completeOneTimeNotification();
            arrivalNotificationRepository.save(notification);
        });
    }

    public void processPending() {
        List<NotificationDelivery> deliveries = deliveryRepository.findAllByStatusIn(
                List.of(NotificationDeliveryStatus.PENDING, NotificationDeliveryStatus.SENDING));
        for (NotificationDelivery delivery : deliveries) {
            try {
                Boolean claimed = new TransactionTemplate(transactionManager)
                        .execute(status -> claim(delivery.getId()));
                if (!Boolean.TRUE.equals(claimed)) continue;
                fcmPushService.sendPushMessage(
                        delivery.getDeviceToken(), delivery.getTitle(), delivery.getBody());
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> markSent(delivery.getId()));
            } catch (Exception e) {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> markPending(delivery.getId()));
                log.error("Notification delivery failed. deliveryId={}, reason={}",
                        delivery.getId(), e.getMessage());
            }
        }
    }

    protected boolean claim(Long deliveryId) {
        NotificationDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() == NotificationDeliveryStatus.SENT) return false;
        if (delivery.getStatus() == NotificationDeliveryStatus.SENDING
                && delivery.getLastAttemptAt() != null
                && delivery.getLastAttemptAt().isAfter(LocalDateTime.now().minus(SENDING_RETRY_DELAY))) {
            return false;
        }
        delivery.markSending(LocalDateTime.now());
        deliveryRepository.save(delivery);
        return true;
    }

    protected void markSent(Long deliveryId) {
        deliveryRepository.findById(deliveryId).ifPresent(delivery -> {
            if (delivery.getStatus() == NotificationDeliveryStatus.SENDING) {
                delivery.markSent();
                deliveryRepository.save(delivery);
            }
        });
    }

    protected void markPending(Long deliveryId) {
        deliveryRepository.findById(deliveryId).ifPresent(delivery -> {
            if (delivery.getStatus() != NotificationDeliveryStatus.SENT) {
                delivery.markPending();
                deliveryRepository.save(delivery);
            }
        });
    }
}
