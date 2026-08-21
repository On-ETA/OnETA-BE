package com.HomeRun.service;

import com.HomeRun.entity.ArrivalNotification;
import com.HomeRun.entity.NotificationDelivery;
import com.HomeRun.entity.NotificationDeliveryStatus;
import com.HomeRun.config.NotificationRetryProperties;
import com.HomeRun.repository.ArrivalNotificationRepository;
import com.HomeRun.repository.NotificationDeliveryRepository;
import com.HomeRun.repository.UserDeviceTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryService {

    private static final Duration SENDING_RETRY_DELAY = Duration.ofMinutes(5);
    private static final int BATCH_SIZE = 100;

    private final NotificationDeliveryRepository deliveryRepository;
    private final ArrivalNotificationRepository arrivalNotificationRepository;
    private final UserDeviceTokenRepository userDeviceTokenRepository;
    private final FcmPushService fcmPushService;
    private final PlatformTransactionManager transactionManager;
    private final NotificationRetryProperties retryProperties;
    private final ScheduledExecutorService notificationRetryExecutor;
    private final Semaphore fcmSemaphore;

    private Clock clock = Clock.systemUTC();
    private final ConcurrentMap<Long, ScheduledFuture<?>> retryTasks = new ConcurrentHashMap<>();

    /** The outbox record is committed before FCM is called. */
    @Transactional
    public void prepare(ArrivalNotification notification, int estimatedDuration,
                        LocalDate deliveryDate, LocalDateTime scheduledAt) {
        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduledAt은 필수입니다.");
        }
        if (deliveryRepository.findByNotificationIdAndDeliveryDate(
                notification.getId(), deliveryDate).isPresent()) return;

        userDeviceTokenRepository.findByUserId(notification.getUser().getId()).ifPresent(token -> {
            String title = "출발 알림: " + notification.getName();
            String body = String.format(
                    "지금 출발하시면 목표 시간(%s)에 도착할 수 있습니다. (예상 소요 시간: %d분)",
                    notification.getTargetArrivalTime(), estimatedDuration);
            LocalDateTime hardDeadlineAt = scheduledAt.plusMinutes(
                    notification.getReminderOffsetMinutes());
            deliveryRepository.save(new NotificationDelivery(
                    notification, deliveryDate, token.getDeviceToken(), title, body,
                    scheduledAt, hardDeadlineAt));
        });
    }

    public void processPending() {
        LocalDateTime now = nowUtc();
        expireOverdueDeliveries(now);
        List<NotificationDelivery> deliveries = deliveryRepository.findProcessable(
                now, now.minus(SENDING_RETRY_DELAY), PageRequest.of(0, BATCH_SIZE));
        for (NotificationDelivery delivery : deliveries) {
            processDelivery(delivery.getId());
        }
    }

    void processDelivery(Long deliveryId) {
        if (!fcmSemaphore.tryAcquire()) {
            LocalDateTime retryAt = new TransactionTemplate(transactionManager)
                    .execute(status -> deferForConcurrency(deliveryId));
            if (retryAt != null) scheduleRetry(deliveryId, retryAt);
            return;
        }
        try {
            Boolean claimed = new TransactionTemplate(transactionManager)
                    .execute(status -> claim(deliveryId));
            if (!Boolean.TRUE.equals(claimed)) return;

            NotificationDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
            if (delivery == null) return;
            fcmPushService.sendPushMessage(
                    delivery.getDeviceToken(), delivery.getTitle(), delivery.getBody(),
                    delivery.getHardDeadlineAt());
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> markSent(deliveryId));
        } catch (Exception e) {
            LocalDateTime retryAt = new TransactionTemplate(transactionManager)
                    .execute(status -> markFailure(deliveryId, e));
            if (retryAt != null) scheduleRetry(deliveryId, retryAt);
            log.error("Notification delivery failed. deliveryId={}, reason={}",
                    deliveryId, e.getMessage());
        } finally {
            fcmSemaphore.release();
        }
    }

    protected boolean claim(Long deliveryId) {
        NotificationDelivery delivery = deliveryRepository.findByIdForUpdate(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() == NotificationDeliveryStatus.SENT
                || delivery.getStatus() == NotificationDeliveryStatus.FAILED
                || delivery.getStatus() == NotificationDeliveryStatus.EXPIRED) return false;
        LocalDateTime now = nowUtc();
        if (delivery.getHardDeadlineAt() == null || !now.isBefore(delivery.getHardDeadlineAt())) {
            delivery.markExpired("DEADLINE", "알림 발송 유효 시간이 지났습니다.");
            completeOneTimeIfNecessary(delivery);
            deliveryRepository.save(delivery);
            return false;
        }
        if (delivery.getStatus() == NotificationDeliveryStatus.SENDING
                && delivery.getLastAttemptAt() != null
                && delivery.getLastAttemptAt().isAfter(now.minus(SENDING_RETRY_DELAY))) {
            return false;
        }
        if (delivery.getStatus() == NotificationDeliveryStatus.PENDING
                && delivery.getNextAttemptAt() != null
                && delivery.getNextAttemptAt().isAfter(now)) {
            return false;
        }
        delivery.markSending(now);
        deliveryRepository.save(delivery);
        return true;
    }

    protected void markSent(Long deliveryId) {
        deliveryRepository.findById(deliveryId).ifPresent(delivery -> {
            if (delivery.getStatus() == NotificationDeliveryStatus.SENDING) {
                LocalDateTime sentAt = nowUtc();
                if (delivery.getHardDeadlineAt() == null
                        || !sentAt.isBefore(delivery.getHardDeadlineAt())) {
                    delivery.markExpired("DEADLINE", "알림 발송 유효 시간이 지났습니다.");
                    completeOneTimeIfNecessary(delivery);
                    deliveryRepository.save(delivery);
                    return;
                }
                delivery.markSentAt(sentAt);
                delivery.getNotification().updateLastSentDate(delivery.getDeliveryDate());
                if (delivery.getNotification().getRepeatDays() == 0) {
                    delivery.getNotification().completeOneTimeNotification();
                }
                arrivalNotificationRepository.save((ArrivalNotification) delivery.getNotification());
                deliveryRepository.save(delivery);
            }
        });
    }

    private LocalDateTime markFailure(Long deliveryId, Exception exception) {
        NotificationDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() == NotificationDeliveryStatus.SENT
                || delivery.getStatus() == NotificationDeliveryStatus.FAILED
                || delivery.getStatus() == NotificationDeliveryStatus.EXPIRED) return null;

        String code = exception instanceof FcmPushException fcm ? fcm.getErrorCode() : "DELIVERY_ERROR";
        String message = exception.getMessage();
        boolean permanent = exception instanceof FcmPushException fcm && fcm.isPermanent();
        if ("DELIVERY_EXPIRED".equals(code)) {
            delivery.markExpired("DEADLINE", message);
            completeOneTimeIfNecessary(delivery);
            deliveryRepository.save(delivery);
            return null;
        }
        if (permanent) {
            delivery.markFailed(code, message);
            if (isTokenPermanentFailure(code)) removeTokenIfStillCurrent(delivery);
            completeOneTimeIfNecessary(delivery);
            deliveryRepository.save(delivery);
            return null;
        }

        LocalDateTime now = nowUtc();
        if (delivery.getHardDeadlineAt() == null || !now.isBefore(delivery.getHardDeadlineAt())) {
            delivery.markExpired("DEADLINE", "알림 발송 유효 시간이 지났습니다.");
            completeOneTimeIfNecessary(delivery);
            deliveryRepository.save(delivery);
            return null;
        }

        Duration delay = calculateRetryDelay(exception, delivery.getAttempts());
        LocalDateTime retryAt = now.plus(delay);
        if (!retryAt.isBefore(delivery.getHardDeadlineAt())) {
            delivery.markExpired("DEADLINE", "다음 재시도 전에 알림 발송 유효 시간이 지났습니다.");
            completeOneTimeIfNecessary(delivery);
            deliveryRepository.save(delivery);
            return null;
        }
        delivery.markTransientFailure(retryAt, code, message);
        deliveryRepository.save(delivery);
        return retryAt;
    }

    private boolean isTokenPermanentFailure(String errorCode) {
        return "UNREGISTERED".equals(errorCode) || "INVALID_ARGUMENT".equals(errorCode);
    }

    private Duration calculateRetryDelay(Exception exception, int attempts) {
        if (exception instanceof FcmPushException fcm && fcm.getRetryAfter() != null) {
            return fcm.getRetryAfter();
        }
        if (exception instanceof FcmPushException fcm && fcm.isQuotaExceeded()) {
            return retryProperties.getQuotaFallbackDelay();
        }

        int exponent = Math.max(0, Math.min(attempts - 1, 30));
        long multiplier = 1L << Math.min(exponent, 20);
        Duration uncapped = retryProperties.getExponentialBaseDelay().multipliedBy(multiplier);
        Duration capped = uncapped.compareTo(retryProperties.getMaxBackoff()) > 0
                ? retryProperties.getMaxBackoff() : uncapped;
        long jitterBound = (long) (capped.toMillis() * retryProperties.getJitterRatio());
        long jitter = jitterBound <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterBound + 1);
        return capped.plusMillis(jitter);
    }

    private LocalDateTime deferForConcurrency(Long deliveryId) {
        NotificationDelivery delivery = deliveryRepository.findByIdForUpdate(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != NotificationDeliveryStatus.PENDING) return null;
        LocalDateTime now = nowUtc();
        if (delivery.getHardDeadlineAt() == null || !now.isBefore(delivery.getHardDeadlineAt())) {
            delivery.markExpired("DEADLINE", "알림 발송 유효 시간이 지났습니다.");
            completeOneTimeIfNecessary(delivery);
            deliveryRepository.save(delivery);
            return null;
        }
        LocalDateTime retryAt = now.plus(retryProperties.getConcurrencyRetryDelay());
        if (!retryAt.isBefore(delivery.getHardDeadlineAt())) {
            delivery.markExpired("DEADLINE", "다음 재시도 전에 알림 발송 유효 시간이 지났습니다.");
            completeOneTimeIfNecessary(delivery);
            deliveryRepository.save(delivery);
            return null;
        }
        delivery.markTransientFailure(retryAt, "LOCAL_CONCURRENCY_LIMIT", "FCM 동시성 제한으로 재시도 대기 중입니다.");
        deliveryRepository.save(delivery);
        return retryAt;
    }

    private void expireOverdueDeliveries(LocalDateTime now) {
        List<NotificationDelivery> deliveries = deliveryRepository.findExpiredCandidates(
                now, PageRequest.of(0, BATCH_SIZE));
        for (NotificationDelivery delivery : deliveries) {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> expireDelivery(delivery.getId(), now));
        }
    }

    private void expireDelivery(Long deliveryId, LocalDateTime now) {
        NotificationDelivery delivery = deliveryRepository.findByIdForUpdate(deliveryId).orElse(null);
        if (delivery == null || (delivery.getStatus() != NotificationDeliveryStatus.PENDING
                && delivery.getStatus() != NotificationDeliveryStatus.SENDING)) return;
        if (delivery.getHardDeadlineAt() == null || !now.isBefore(delivery.getHardDeadlineAt())) {
            delivery.markExpired("DEADLINE", "알림 발송 유효 시간이 지났습니다.");
            completeOneTimeIfNecessary(delivery);
            deliveryRepository.save(delivery);
        }
    }

    private void scheduleRetry(Long deliveryId, LocalDateTime retryAt) {
        long delayMillis = Math.max(0,
                Duration.between(clock.instant(), retryAt.toInstant(ZoneOffset.UTC)).toMillis());
        retryTasks.compute(deliveryId, (id, current) -> {
            if (current != null && !current.isDone()) return current;
            return notificationRetryExecutor.schedule(() -> {
                retryTasks.remove(id);
                processDelivery(id);
            }, delayMillis, TimeUnit.MILLISECONDS);
        });
    }

    private void removeTokenIfStillCurrent(NotificationDelivery delivery) {
        Long userId = delivery.getNotification().getUser().getId();
        userDeviceTokenRepository.findByUserIdAndDeviceToken(userId, delivery.getDeviceToken())
                .ifPresent(userDeviceTokenRepository::delete);
    }

    private void completeOneTimeIfNecessary(NotificationDelivery delivery) {
        if (delivery.getNotification().getRepeatDays() == 0) {
            delivery.getNotification().completeOneTimeNotification();
            arrivalNotificationRepository.save((ArrivalNotification) delivery.getNotification());
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
