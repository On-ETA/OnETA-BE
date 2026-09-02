package com.OnETA.scheduler;

import com.HomeRun.scheduler.NotificationScheduler;
import com.OnETA.entity.*;
import com.OnETA.repository.ArrivalNotificationRepository;
import com.OnETA.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FirstLastNotificationSchedulerTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void doesNotCreateBeforeBaseAndCreatesBaseWhenDue() {
        ArrivalNotification n = notification(NotificationScheduleType.FIRST_TRANSIT);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        when(schedules.evaluate(any(), any(), any(), any())).thenReturn(
                new TransitScheduleService.Decision(LocalDateTime.of(2026, 8, 27, 5, 10),
                        LocalDateTime.of(2026, 8, 27, 5, 20), DeliveryPhase.BASE,
                        LocalDateTime.of(2026, 8, 27, 5, 20), LocalDateTime.of(2026, 8, 27, 5, 20), false, 30));
        NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
        NotificationScheduler scheduler = scheduler(n, schedules, delivery, "2026-08-27T05:09");
        scheduler.scheduleArrivalNotifications();
        verify(delivery, never()).prepare(any(), anyInt(), any(), any(), anyInt(), any(), any());

        ReflectionTestUtils.setField(scheduler, "clock", Clock.fixed(at("2026-08-27T05:10"), ZoneOffset.UTC));
        scheduler.scheduleArrivalNotifications();
        verify(delivery).prepare(eq(n), eq(30), eq(LocalDate.of(2026, 8, 27)),
                any(LocalDateTime.class), anyInt(), any(LocalDateTime.class), eq(DeliveryPhase.BASE));
    }

    @Test
    void recoveryIsCreatedImmediatelyWhenScheduledTimePassedButBoardingIsFuture() {
        ArrivalNotification n = notification(NotificationScheduleType.FIRST_TRANSIT);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        when(schedules.evaluate(any(), any(), any(), any())).thenReturn(
                new TransitScheduleService.Decision(LocalDateTime.of(2026, 8, 27, 5, 20),
                        LocalDateTime.of(2026, 8, 27, 5, 40), DeliveryPhase.RECOVERY,
                        LocalDateTime.of(2026, 8, 27, 5, 20), LocalDateTime.of(2026, 8, 27, 5, 20), true, 30));
        NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
        NotificationScheduler scheduler = scheduler(n, schedules, delivery, "2026-08-27T05:32");
        scheduler.scheduleArrivalNotifications();
        verify(delivery).prepare(eq(n), eq(30), eq(LocalDate.of(2026, 8, 27)),
                any(LocalDateTime.class), anyInt(), any(LocalDateTime.class), eq(DeliveryPhase.RECOVERY));
    }

    @Test
    void lastUsesScheduleServiceButNeverDirectRealtimeDurationPath() {
        ArrivalNotification n = notification(NotificationScheduleType.LAST_TRANSIT);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        when(schedules.evaluate(any(), any(), any(), any())).thenReturn(
                new TransitScheduleService.Decision(LocalDateTime.of(2026, 8, 27, 23, 10),
                        LocalDateTime.of(2026, 8, 27, 23, 20), DeliveryPhase.BASE,
                        LocalDateTime.of(2026, 8, 27, 23, 20), LocalDateTime.of(2026, 8, 27, 23, 20), false, 30));
        NotificationDeliveryService delivery = mock(NotificationDeliveryService.class);
        TransitApiService transit = mock(TransitApiService.class);
        NotificationScheduler scheduler = new NotificationScheduler(mock(ArrivalNotificationRepository.class), transit,
                new RepeatDaysService(), delivery, schedules);
        ReflectionTestUtils.setField(scheduler, "timeZone", "Asia/Seoul");
        ReflectionTestUtils.setField(scheduler, "clock", Clock.fixed(at("2026-08-27T23:10"), ZoneOffset.UTC));
        when(schedulerRepository(scheduler).findAllByIsActiveTrue()).thenReturn(List.of(n));
        scheduler.scheduleArrivalNotifications();
        verify(transit, never()).getRealTimeDuration(anyString());
        verify(delivery).prepare(any(), anyInt(), any(), any(), anyInt(), any(), eq(DeliveryPhase.BASE));
    }

    private ArrivalNotification notification(NotificationScheduleType type) {
        ArrivalNotification n = mock(ArrivalNotification.class);
        when(n.getScheduleType()).thenReturn(type); when(n.getRepeatDays()).thenReturn(0);
        when(n.getLastSentDate()).thenReturn(null); when(n.getReminderOffsetMinutes()).thenReturn(10);
        when(n.getReminderOffsetMinutesList()).thenReturn(List.of(10)); when(n.getRouteDetails()).thenReturn("route");
        when(n.getIsActive()).thenReturn(true); when(n.getTargetArrivalTime()).thenReturn(LocalTime.of(18, 0));
        return n;
    }
    private NotificationScheduler scheduler(ArrivalNotification n, TransitScheduleService schedules,
                                             NotificationDeliveryService delivery, String time) {
        ArrivalNotificationRepository repository = mock(ArrivalNotificationRepository.class);
        when(repository.findAllByIsActiveTrue()).thenReturn(List.of(n));
        NotificationScheduler result = new NotificationScheduler(repository, mock(TransitApiService.class),
                new RepeatDaysService(), delivery, schedules);
        ReflectionTestUtils.setField(result, "timeZone", "Asia/Seoul");
        ReflectionTestUtils.setField(result, "clock", Clock.fixed(at(time), ZoneOffset.UTC));
        return result;
    }
    private ArrivalNotificationRepository schedulerRepository(NotificationScheduler scheduler) {
        return (ArrivalNotificationRepository) ReflectionTestUtils.getField(scheduler, "arrivalNotificationRepository");
    }
    private Instant at(String value) { return ZonedDateTime.parse(value + "+09:00").toInstant(); }
}
