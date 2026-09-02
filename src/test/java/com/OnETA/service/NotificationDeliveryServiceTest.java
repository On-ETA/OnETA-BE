package com.OnETA.service;

import com.OnETA.entity.ArrivalNotification;
import com.OnETA.entity.NotificationDelivery;
import com.OnETA.entity.NotificationDeliveryStatus;
import com.OnETA.entity.User;
import com.OnETA.entity.UserDeviceToken;
import com.OnETA.entity.DeliveryPhase;
import com.OnETA.config.NotificationRetryProperties;
import com.OnETA.repository.ArrivalNotificationRepository;
import com.OnETA.repository.NotificationDeliveryRepository;
import com.OnETA.repository.UserDeviceTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeliveryServiceTest {

    @Test
    void sentDeliveryIsNotDispatchedAgain() {
        NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
        ArrivalNotificationRepository notifications = mock(ArrivalNotificationRepository.class);
        UserDeviceTokenRepository tokens = mock(UserDeviceTokenRepository.class);
        FcmPushService fcm = mock(FcmPushService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(null);

        ArrivalNotification notification = mock(ArrivalNotification.class);
        when(notification.getRepeatDays()).thenReturn(21);
        NotificationDelivery delivery = new NotificationDelivery(
                notification, LocalDate.of(2026, 8, 10), "token", "title", "body",
                java.time.LocalDateTime.of(2026, 8, 10, 9, 0),
                java.time.LocalDateTime.of(2026, 8, 10, 9, 10));
        ReflectionTestUtils.setField(delivery, "id", 1L);
        when(deliveries.findProcessable(any(), any(), any())).thenReturn(List.of(delivery));
        when(deliveries.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        when(deliveries.findById(1L)).thenReturn(Optional.of(delivery));
        ScheduledExecutorService retryExecutor = mock(ScheduledExecutorService.class);

        NotificationDeliveryService service = new NotificationDeliveryService(
                deliveries, notifications, tokens, fcm, transactionManager,
                retryProperties(), retryExecutor, new Semaphore(4));
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-08-10T09:00:00Z"), ZoneOffset.UTC));

        service.processPending();
        service.processPending();

        verify(fcm, times(1)).sendPushMessage("token", "title", "body",
                java.time.LocalDateTime.of(2026, 8, 10, 9, 10));
        verify(deliveries, times(2)).save(delivery);
    }

    @Test
    void transientFailureSchedulesFirstRetryAfterTwoSeconds() {
        TestFixture fixture = fixture();
        doThrow(new FcmPushException("UNAVAILABLE", "temporarily unavailable", false, null))
                .when(fixture.fcm).sendPushMessage(any(), any(), any(), any());

        fixture.service.processPending();

        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(fixture.delivery.getAttempts()).isEqualTo(1);
        assertThat(fixture.delivery.getNextAttemptAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 10, 9, 0, 10));
    }

    @Test
    void secondAndThirdFailuresUseFiveAndTenSecondDelays() {
        TestFixture secondAttempt = fixture();
        ReflectionTestUtils.setField(secondAttempt.delivery, "attempts", 1);
        doThrow(new FcmPushException("UNAVAILABLE", "temporarily unavailable", false, null))
                .when(secondAttempt.fcm).sendPushMessage(any(), any(), any(), any());
        secondAttempt.service.processPending();
        assertThat(secondAttempt.delivery.getNextAttemptAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 10, 9, 0, 20));

        TestFixture thirdAttempt = fixture();
        ReflectionTestUtils.setField(thirdAttempt.delivery, "attempts", 2);
        doThrow(new FcmPushException("UNAVAILABLE", "temporarily unavailable", false, null))
                .when(thirdAttempt.fcm).sendPushMessage(any(), any(), any(), any());
        thirdAttempt.service.processPending();
        assertThat(thirdAttempt.delivery.getNextAttemptAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 10, 9, 0, 40));
    }

    @Test
    void retryAfterTakesPriorityForQuotaFailure() {
        TestFixture fixture = fixture();
        doThrow(new FcmPushException("QUOTA_EXCEEDED", "quota", false,
                Duration.ofSeconds(60), null))
                .when(fixture.fcm).sendPushMessage(any(), any(), any(), any());

        fixture.service.processPending();

        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(fixture.delivery.getNextAttemptAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 10, 9, 1));
    }

    @Test
    void fourthAttemptFailureRemainsRetryableBeforeDeadline() {
        TestFixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.delivery, "attempts", 3);
        doThrow(new FcmPushException("UNAVAILABLE", "temporarily unavailable", false, null))
                .when(fixture.fcm).sendPushMessage(any(), any(), any(), any());

        fixture.service.processPending();

        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(fixture.delivery.getNextAttemptAt()).isNotNull();
    }

    @Test
    void deliveryBeforeNextAttemptAtCannotBeClaimed() {
        TestFixture fixture = fixture();
        fixture.delivery.markTransientFailure(
                java.time.LocalDateTime.of(2026, 8, 10, 9, 1), "UNAVAILABLE", "retry");

        assertThat(fixture.service.claim(1L)).isFalse();
        assertThat(fixture.delivery.getAttempts()).isZero();
    }

    @Test
    void attemptsBeyondPreviousLimitRemainRetryableBeforeDeadline() {
        TestFixture fixture = fixture();
        when(fixture.notification.getRepeatDays()).thenReturn(0);
        ReflectionTestUtils.setField(fixture.delivery, "attempts", 4);
        doThrow(new FcmPushException("UNAVAILABLE", "temporarily unavailable", false, null))
                .when(fixture.fcm).sendPushMessage(any(), any(), any(), any());

        fixture.service.processPending();

        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(fixture.delivery.getAttempts()).isEqualTo(5);
        assertThat(fixture.delivery.getNextAttemptAt()).isNotNull();
    }

    @Test
    void unregisteredTokenFailsAndDeletesOnlyTheSameCurrentToken() {
        TestFixture fixture = fixture();
        doThrow(new FcmPushException("UNREGISTERED", "unregistered", true, null))
                .when(fixture.fcm).sendPushMessage(any(), any(), any(), any());
        UserDeviceToken token = mock(UserDeviceToken.class);
        when(token.getDeviceToken()).thenReturn("token");
        when(fixture.tokens.findByUserIdAndDeviceToken(1L, "token")).thenReturn(Optional.of(token));

        fixture.service.processPending();

        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        verify(fixture.tokens).delete(token);
    }

    @Test
    void configurationPermanentFailureDoesNotDeleteToken() {
        TestFixture fixture = fixture();
        doThrow(new FcmPushException("SENDER_ID_MISMATCH", "sender mismatch", true, null))
                .when(fixture.fcm).sendPushMessage(any(), any(), any(), any());

        fixture.service.processPending();

        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        verify(fixture.tokens, never()).delete(any());
    }

    @Test
    void updatedTokenIsNotDeletedAfterOldTokenFails() {
        TestFixture fixture = fixture();
        doThrow(new FcmPushException("UNREGISTERED", "unregistered", true, null))
                .when(fixture.fcm).sendPushMessage(any(), any(), any(), any());
        when(fixture.tokens.findByUserIdAndDeviceToken(1L, "token")).thenReturn(Optional.empty());

        fixture.service.processPending();

        verify(fixture.tokens, never()).delete(any());
    }

    @Test
    void prepareDoesNotUpdateNotificationSentState() {
        TestFixture fixture = fixture();
        ArrivalNotification notification = fixture.notification;
        User user = fixture.user;
        when(notification.getId()).thenReturn(2L);
        when(notification.getUser()).thenReturn(user);
        when(notification.getName()).thenReturn("출근");
        when(notification.getTargetArrivalTime()).thenReturn(java.time.LocalTime.of(18, 30));
        when(notification.getReminderOffsetMinutes()).thenReturn(10);
        when(user.getId()).thenReturn(1L);
        UserDeviceToken token = mock(UserDeviceToken.class);
        when(token.getDeviceToken()).thenReturn("token");

        NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
        when(deliveries.findByNotificationIdAndDeliveryDate(2L, LocalDate.of(2026, 8, 10)))
                .thenReturn(Optional.empty());
        UserDeviceTokenRepository tokens = mock(UserDeviceTokenRepository.class);
        when(tokens.findByUserId(1L)).thenReturn(Optional.of(token));
        NotificationDeliveryService service = new NotificationDeliveryService(
                deliveries, mock(ArrivalNotificationRepository.class), tokens,
                mock(FcmPushService.class), mock(PlatformTransactionManager.class),
                retryProperties(), mock(ScheduledExecutorService.class), new Semaphore(4));

        LocalDateTime scheduledAt = LocalDateTime.of(2026, 8, 10, 8, 59, 58);
        service.prepare(notification, 30, LocalDate.of(2026, 8, 10), scheduledAt);

        verify(notification, never()).updateLastSentDate(any());
        verify(notification, never()).completeOneTimeNotification();
        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveries).save(deliveryCaptor.capture());
        assertThat(deliveryCaptor.getValue().getScheduledAt()).isEqualTo(scheduledAt);
        assertThat(deliveryCaptor.getValue().getHardDeadlineAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 10, 9, 9, 58));
    }

    @Test
    void deadlineReachedPreventsFcmCallAndExpiresDelivery() {
        TestFixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.service, "clock",
                Clock.fixed(Instant.parse("2026-08-10T09:10:00Z"), ZoneOffset.UTC));

        fixture.service.processPending();

        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.EXPIRED);
        verify(fixture.fcm, never()).sendPushMessage(any(), any(), any(), any());
    }

    @Test
    void successfulDeliveryStoresSentAtAndCanCalculateLatency() {
        TestFixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.delivery, "scheduledAt",
                java.time.LocalDateTime.of(2026, 8, 10, 8, 59, 58));

        fixture.service.processDelivery(1L);

        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(fixture.delivery.getSentAt())
                .isEqualTo(java.time.LocalDateTime.of(2026, 8, 10, 9, 0));
        assertThat(fixture.delivery.getDeliveryLatency()).isEqualTo(java.time.Duration.ofSeconds(2));
    }

    @Test
    void recoveryDeliveryUsesExistingRetryPipelineAndIsSentOnlyOnce() {
        TestFixture fixture = fixture();
        ReflectionTestUtils.setField(fixture.delivery, "deliveryPhase", DeliveryPhase.RECOVERY);
        doThrow(new FcmPushException("UNAVAILABLE", "temporary", false, null))
                .doNothing().when(fixture.fcm).sendPushMessage(any(), any(), any(), any());

        fixture.service.processPending();
        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        ReflectionTestUtils.setField(fixture.service, "clock",
                Clock.fixed(Instant.parse("2026-08-10T09:00:11Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(fixture.delivery, "nextAttemptAt",
                LocalDateTime.of(2026, 8, 10, 9, 0, 10));
        fixture.service.processPending();

        assertThat(fixture.delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        verify(fixture.fcm, times(2)).sendPushMessage("token", "title", "body",
                LocalDateTime.of(2026, 8, 10, 9, 10));
    }

    private TestFixture fixture() {
        NotificationDeliveryRepository deliveries = mock(NotificationDeliveryRepository.class);
        ArrivalNotificationRepository notifications = mock(ArrivalNotificationRepository.class);
        UserDeviceTokenRepository tokens = mock(UserDeviceTokenRepository.class);
        FcmPushService fcm = mock(FcmPushService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(null);
        ArrivalNotification notification = mock(ArrivalNotification.class);
        User user = mock(User.class);
        when(notification.getRepeatDays()).thenReturn(21);
        when(notification.getUser()).thenReturn(user);
        when(user.getId()).thenReturn(1L);
        NotificationDelivery delivery = new NotificationDelivery(
                notification, LocalDate.of(2026, 8, 10), "token", "title", "body",
                java.time.LocalDateTime.of(2026, 8, 10, 9, 0),
                java.time.LocalDateTime.of(2026, 8, 10, 9, 10));
        ReflectionTestUtils.setField(delivery, "id", 1L);
        when(deliveries.findProcessable(any(), any(), any())).thenReturn(List.of(delivery));
        when(deliveries.findByIdForUpdate(1L)).thenReturn(Optional.of(delivery));
        when(deliveries.findById(1L)).thenReturn(Optional.of(delivery));
        NotificationDeliveryService service = new NotificationDeliveryService(
                deliveries, notifications, tokens, fcm, transactionManager,
                retryProperties(), mock(ScheduledExecutorService.class), new Semaphore(4));
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-08-10T09:00:00Z"), ZoneOffset.UTC));
        return new TestFixture(service, delivery, notification, user, tokens, fcm);
    }

    private NotificationRetryProperties retryProperties() {
        NotificationRetryProperties properties = new NotificationRetryProperties();
        properties.setJitterRatio(0.0);
        return properties;
    }

    private record TestFixture(NotificationDeliveryService service, NotificationDelivery delivery,
                               ArrivalNotification notification, User user,
                               UserDeviceTokenRepository tokens, FcmPushService fcm) {
    }
}
