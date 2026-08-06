package com.HomeRun.scheduler;

import com.HomeRun.entity.ArrivalNotification;
import com.HomeRun.entity.User;
import com.HomeRun.entity.UserDeviceToken;
import com.HomeRun.repository.ArrivalNotificationRepository;
import com.HomeRun.repository.UserDeviceTokenRepository;
import com.HomeRun.service.FcmPushService;
import com.HomeRun.service.RepeatDaysService;
import com.HomeRun.service.TransitApiService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class NotificationSchedulerTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void oneTimeNotificationSendsAtTodaysCalculatedTimeAndEnds() {
        ArrivalNotification notification = notification(0);
        SchedulerDependencies dependencies = dependencies(notification, at("2026-08-10T17:59"));

        dependencies.scheduler.scheduleArrivalNotifications();
        verifyNoInteractions(dependencies.fcm);

        ReflectionTestUtils.setField(dependencies.scheduler, "clock", fixedClock("2026-08-10T18:00"));

        dependencies.scheduler.scheduleArrivalNotifications();

        verify(dependencies.fcm).sendPushMessage(eq("token"), any(), any());
        verify(notification).updateLastSentDate(LocalDate.of(2026, 8, 10));
        verify(notification).completeOneTimeNotification();
        verify(dependencies.notifications).save(notification);
    }

    @Test
    void oneTimeNotificationAfterTodaysTimeWaitsUntilTomorrow() {
        ArrivalNotification notification = notification(0);
        SchedulerDependencies dependencies = dependencies(notification, at("2026-08-10T18:20"));

        dependencies.scheduler.scheduleArrivalNotifications();
        verifyNoInteractions(dependencies.fcm);
        verify(dependencies.notifications, never()).save(any());

        ReflectionTestUtils.setField(dependencies.scheduler, "clock", fixedClock("2026-08-11T18:00"));
        dependencies.scheduler.scheduleArrivalNotifications();

        verify(dependencies.fcm).sendPushMessage(eq("token"), any(), any());
        verify(notification).completeOneTimeNotification();
    }

    @Test
    void repeatingNotificationSendsAtTodaysCalculatedTime() {
        // MON/WED/FRI = 1 + 4 + 16 = 21
        ArrivalNotification notification = notification(21);
        SchedulerDependencies dependencies = dependencies(notification, at("2026-08-10T17:59"));

        dependencies.scheduler.scheduleArrivalNotifications();
        verifyNoInteractions(dependencies.fcm);

        ReflectionTestUtils.setField(dependencies.scheduler, "clock", fixedClock("2026-08-10T18:00"));

        dependencies.scheduler.scheduleArrivalNotifications();

        verify(dependencies.fcm).sendPushMessage(eq("token"), any(), any());
        verify(notification).updateLastSentDate(LocalDate.of(2026, 8, 10));
        verify(notification, never()).completeOneTimeNotification();
        assertThat(notification.getIsActive()).isTrue();
    }

    @Test
    void repeatingNotificationAfterTodaysTimeWaitsUntilNextSelectedDay() {
        ArrivalNotification notification = notification(21);
        SchedulerDependencies dependencies = dependencies(notification, at("2026-08-10T18:20"));

        dependencies.scheduler.scheduleArrivalNotifications();
        verifyNoInteractions(dependencies.fcm);

        // The next selected day is Wednesday.
        ReflectionTestUtils.setField(dependencies.scheduler, "clock", fixedClock("2026-08-12T18:00"));
        dependencies.scheduler.scheduleArrivalNotifications();

        verify(dependencies.fcm).sendPushMessage(eq("token"), any(), any());
        verify(notification, never()).completeOneTimeNotification();
        assertThat(notification.getIsActive()).isTrue();
    }

    @Test
    void repeatingNotificationOnNonSelectedDayDoesNotCallTransitApi() {
        ArrivalNotification notification = notification(21);
        SchedulerDependencies dependencies = dependencies(notification, at("2026-08-11T18:00"));

        dependencies.scheduler.scheduleArrivalNotifications();

        verifyNoInteractions(dependencies.transit);
        verifyNoInteractions(dependencies.fcm);
    }

    @Test
    void fcmFailureDoesNotUpdateNotificationState() {
        ArrivalNotification notification = notification(0);
        SchedulerDependencies dependencies = dependencies(notification, at("2026-08-10T18:00"));
        doThrow(new IllegalStateException("FCM failure"))
                .when(dependencies.fcm).sendPushMessage(any(), any(), any());

        dependencies.scheduler.scheduleArrivalNotifications();

        verify(notification, never()).updateLastSentDate(any());
        verify(notification, never()).completeOneTimeNotification();
        verify(dependencies.notifications, never()).save(any());
    }

    private ArrivalNotification notification(int repeatDays) {
        ArrivalNotification notification = mock(ArrivalNotification.class);
        User user = mock(User.class);
        when(notification.getRepeatDays()).thenReturn(repeatDays);
        when(notification.getLastSentDate()).thenReturn(null);
        when(notification.getTargetArrivalTime()).thenReturn(LocalTime.of(18, 30));
        when(notification.getReminderOffsetMinutes()).thenReturn(0);
        when(notification.getRouteDetails()).thenReturn("route");
        when(notification.getUser()).thenReturn(user);
        when(notification.getName()).thenReturn("출근");
        when(notification.getIsActive()).thenReturn(true);
        when(user.getId()).thenReturn(1L);
        return notification;
    }

    private SchedulerDependencies dependencies(ArrivalNotification notification, Instant instant) {
        ArrivalNotificationRepository notifications = mock(ArrivalNotificationRepository.class);
        UserDeviceTokenRepository tokens = mock(UserDeviceTokenRepository.class);
        TransitApiService transit = mock(TransitApiService.class);
        FcmPushService fcm = mock(FcmPushService.class);
        UserDeviceToken token = mock(UserDeviceToken.class);
        when(notifications.findAllByIsActiveTrue()).thenReturn(List.of(notification));
        when(tokens.findByUserId(1L)).thenReturn(Optional.of(token));
        when(token.getDeviceToken()).thenReturn("token");
        when(transit.getRealTimeDuration("route")).thenReturn(30);

        NotificationScheduler scheduler = new NotificationScheduler(
                notifications, tokens, transit, fcm, new RepeatDaysService());
        ReflectionTestUtils.setField(scheduler, "timeZone", "Asia/Seoul");
        ReflectionTestUtils.setField(scheduler, "clock", Clock.fixed(instant, ZoneOffset.UTC));
        return new SchedulerDependencies(scheduler, notifications, transit, fcm);
    }

    private Instant at(String localDateTime) {
        return ZonedDateTime.parse(localDateTime + "+09:00").toInstant();
    }

    private Clock fixedClock(String localDateTime) {
        return Clock.fixed(at(localDateTime), ZoneOffset.UTC);
    }

    private record SchedulerDependencies(
            NotificationScheduler scheduler,
            ArrivalNotificationRepository notifications,
            TransitApiService transit,
            FcmPushService fcm) {
    }
}
