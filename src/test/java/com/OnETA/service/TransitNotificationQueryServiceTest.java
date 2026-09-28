package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitNotificationDto;
import com.OnETA.entity.*;
import com.OnETA.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransitNotificationQueryServiceTest {
    private final ArrivalNotificationRepository notifications = mock(ArrivalNotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TransitScheduleService schedules = mock(TransitScheduleService.class);
    private final User user = mock(User.class);
    private final TransitNotificationQueryService service = new TransitNotificationQueryService(notifications, users, schedules);

    @BeforeEach void setup() {
        when(user.getId()).thenReturn(1L);
        when(users.findByEmail("me")).thenReturn(Optional.of(user));
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(Instant.parse("2026-09-27T14:50:00Z"), ZoneOffset.UTC));
    }

    @Test void showsDepartureAndSignedCountdownAcrossMidnightAndExcludesNormalSchedules() {
        var future = notification(1L, NotificationScheduleType.LAST_TRANSIT);
        var past = notification(2L, NotificationScheduleType.FIRST_TRANSIT);
        var normal = notification(3L, NotificationScheduleType.NORMAL);
        when(notifications.findAllByUserId(1L)).thenReturn(List.of(future, past, normal));
        when(schedules.estimateDeparture(eq(future), any(), any())).thenReturn(LocalDateTime.parse("2026-09-28T00:10:00"));
        when(schedules.estimateDeparture(eq(past), any(), any())).thenReturn(LocalDateTime.parse("2026-09-27T23:45:00"));

        past.archiveTransit();
        var response = service.getCurrentNotification("me");
        assertThat(response.getNotificationId()).isEqualTo(1L);
        assertThat(response.getEstimatedDepartureAt()).isEqualTo(OffsetDateTime.parse("2026-09-28T00:10:00+09:00"));
        assertThat(response.getServerTime()).isEqualTo(OffsetDateTime.parse("2026-09-27T23:50:00+09:00"));
        assertThat(response.getRemainingSeconds()).isEqualTo(1200L);
        assertThat(response.getEstimateStatus()).isEqualTo(TransitNotificationDto.EstimateStatus.ESTIMATED);
        assertThat(new tools.jackson.databind.ObjectMapper().writeValueAsString(response)).doesNotContain("routeName");
        verify(schedules, never()).estimateDeparture(eq(past), any(), any());
        verify(schedules, never()).estimateDeparture(eq(normal), any(), any());
        future.toggleActive(false);
        when(schedules.estimateDeparture(eq(future), any(), any())).thenReturn(LocalDateTime.parse("2026-09-27T23:45:00"));
        assertThat(service.getCurrentNotification("me").getRemainingSeconds()).isEqualTo(-300L);
    }

    @Test void unavailableTimetablePreservesSettingWithoutPretendingZeroRemaining() {
        var missing = notification(1L, NotificationScheduleType.FIRST_TRANSIT);
        when(notifications.findAllByUserId(1L)).thenReturn(List.of(missing));
        when(schedules.estimateDeparture(eq(missing), any(), any()))
                .thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
        var response = service.getCurrentNotification("me");
        assertThat(response.getEstimatedDepartureAt()).isNull();
        assertThat(response.getRemainingSeconds()).isNull();
        assertThat(response.getEstimateStatus()).isEqualTo(TransitNotificationDto.EstimateStatus.UNAVAILABLE);
        assertThat(response.getEstimateErrorCode()).isEqualTo("T005");
    }

    @Test void detailChecksOwnershipAndTypeBeforeCalculating() {
        var foreign = notification(1L, NotificationScheduleType.LAST_TRANSIT);
        var other = mock(User.class); when(other.getId()).thenReturn(2L);
        ReflectionTestUtils.setField(foreign, "user", other);
        when(notifications.findById(1L)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.getNotification("me", 1L)).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.HANDLE_ACCESS_DENIED));
        ReflectionTestUtils.setField(foreign, "user", user);
        foreign.archiveTransit();
        assertThatThrownBy(() -> service.getNotification("me", 1L)).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
        when(notifications.findById(1L)).thenReturn(Optional.of(notification(1L, NotificationScheduleType.NORMAL)));
        assertThatThrownBy(() -> service.getNotification("me", 1L)).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
        verifyNoInteractions(schedules);
    }

    @Test void absentSettingReturnsNoDataInsteadOfAList() {
        var archived = notification(1L, NotificationScheduleType.FIRST_TRANSIT);
        archived.archiveTransit();
        when(notifications.findAllByUserId(1L)).thenReturn(List.of(archived));
        assertThat(service.getCurrentNotification("me")).isNull();
        var controller = new com.OnETA.controller.ScheduleNotificationController(mock(NotificationService.class), service);
        var json = new tools.jackson.databind.ObjectMapper().writeValueAsString(controller.transit(() -> "me"));
        assertThat(json).contains("SUCCESS").doesNotContain("\"data\"", "[]");
        verifyNoInteractions(schedules);
    }

    private ArrivalNotification notification(Long id, NotificationScheduleType type) {
        var notification = new ArrivalNotification(user, "route", List.of(10), 0, LocalTime.NOON, "{}", type);
        ReflectionTestUtils.setField(notification, "id", id);
        return notification;
    }
}
