package com.OnETA.service;

import com.OnETA.entity.ArrivalNotification;
import com.OnETA.entity.NotificationDelivery;
import com.OnETA.entity.NotificationDeliveryStatus;
import com.OnETA.entity.Role;
import com.OnETA.entity.User;
import com.OnETA.entity.UserDeviceToken;
import com.OnETA.repository.ArrivalNotificationRepository;
import com.OnETA.repository.NotificationDeliveryRepository;
import com.OnETA.repository.UserRepository;
import com.OnETA.repository.UserDeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
class NotificationDeliveryConcurrencyTest {

    @MockitoBean
    private FcmPushService fcmPushService;

    @Autowired
    private NotificationDeliveryService deliveryService;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private ArrivalNotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserDeviceTokenRepository deviceTokenRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void simultaneousProcessingAllowsOnlyOneClaim() throws Exception {
        User user = userRepository.save(new User(
                "claim-race-" + System.nanoTime() + "@example.com", "password", "tester", Role.USER));
        ArrivalNotification notification = notificationRepository.saveAndFlush(
                new ArrivalNotification(user, "race", 10, 21, LocalTime.of(18, 30), "route"));
        String deviceToken = "race-token-" + System.nanoTime();
        deviceTokenRepository.saveAndFlush(new UserDeviceToken(user, deviceToken));
        NotificationDelivery delivery = new NotificationDelivery(
                notification, LocalDate.of(2026, 8, 10), deviceToken,
                "title", "body", LocalDateTime.now(ZoneOffset.UTC),
                LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10));
        ReflectionTestUtils.setField(delivery, "attempts", 3);
        delivery = deliveryRepository.saveAndFlush(delivery);
        Long deliveryId = delivery.getId();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        var first = callers.submit(() -> processWhenReleased(start, deliveryId));
        var second = callers.submit(() -> processWhenReleased(start, deliveryId));
        start.countDown();

        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        callers.shutdownNow();

        NotificationDelivery result = deliveryRepository.findById(deliveryId).orElseThrow();
        assertThat(result.getAttempts()).isEqualTo(4);
        assertThat(result.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        verify(fcmPushService, times(1)).sendPushMessage(any(), any(), any(), any(), any());
    }

    @Test
    void editWhileFcmIsSendingPreservesNewRevisionAndAcceptedTimestamp() throws Exception {
        User user = userRepository.save(new User(
                "revision-send-race-" + System.nanoTime() + "@example.com",
                "password", "tester", Role.USER));
        ArrivalNotification notification = notificationRepository.saveAndFlush(
                new ArrivalNotification(user, "original", 10, 21, LocalTime.of(18, 30), "route"));
        String token = "revision-send-token-" + System.nanoTime();
        deviceTokenRepository.saveAndFlush(new UserDeviceToken(user, token));
        LocalDate date = LocalDate.of(2026, 10, 28);
        LocalDateTime scheduled = LocalDateTime.now(ZoneOffset.UTC);
        NotificationDelivery oldDelivery = deliveryRepository.saveAndFlush(
                new NotificationDelivery(notification, date, 10, token, "original", "original",
                        scheduled, scheduled.plusMinutes(10)));
        Long oldId = oldDelivery.getId();
        Long notificationId = notification.getId();

        CountDownLatch enteredFcm = new CountDownLatch(1);
        CountDownLatch releaseFcm = new CountDownLatch(1);
        doAnswer(invocation -> {
            enteredFcm.countDown();
            if (!releaseFcm.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FCM test hold timed out");
            }
            return null;
        }).when(fcmPushService).sendPushMessage(any(), any(), any(), any(), any());

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> sending = caller.submit(() -> deliveryService.processDelivery(oldId));
            assertThat(enteredFcm.await(10, TimeUnit.SECONDS)).isTrue();

            Long revisedId = transactionTemplate.execute(status -> {
                ArrivalNotification current = notificationRepository.findById(notificationId).orElseThrow();
                current.updateArrivalInfo(LocalTime.of(19, 30), null);
                current.advanceScheduleRevision();
                deliveryRepository.expireSupersededDeliveries(notificationId, current.getScheduleRevision());
                NotificationDelivery revised = deliveryRepository.saveAndFlush(
                        new NotificationDelivery(current, date, 10, token, "revised", "revised",
                                scheduled.plusMinutes(20), scheduled.plusMinutes(30)));
                return revised.getId();
            });

            assertThat(deliveryRepository.findById(oldId).orElseThrow().getStatus())
                    .isEqualTo(NotificationDeliveryStatus.EXPIRED);
            releaseFcm.countDown();
            sending.get(20, TimeUnit.SECONDS);

            NotificationDelivery oldResult = deliveryRepository.findById(oldId).orElseThrow();
            NotificationDelivery newResult = deliveryRepository.findById(revisedId).orElseThrow();
            ArrivalNotification current = notificationRepository.findById(notificationId).orElseThrow();
            assertThat(oldResult.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
            assertThat(oldResult.getLastErrorCode()).isEqualTo("SCHEDULE_REVISED");
            assertThat(oldResult.getSentAt()).isNotNull();
            assertThat(newResult.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
            assertThat(newResult.getScheduleRevision()).isEqualTo(1L);
            assertThat(current.getScheduleRevision()).isEqualTo(1L);
            assertThat(current.getLastSentDate()).isNull();
            assertThat(current.getTargetArrivalTime()).isEqualTo(LocalTime.of(19, 30));
            verify(fcmPushService, times(1)).sendPushMessage(any(), any(), any(), any(), any());
        } finally {
            releaseFcm.countDown();
            caller.shutdownNow();
        }
    }

    private void processWhenReleased(CountDownLatch start, Long deliveryId) {
        try {
            start.await();
            deliveryService.processDelivery(deliveryId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
