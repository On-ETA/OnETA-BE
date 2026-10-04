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

    @Test void returnsOneActiveSettingPerTransitTypeAndExcludesNormalArchivedAndInactive() {
        var last = notification(1L, NotificationScheduleType.LAST_TRANSIT);
        var first = notification(2L, NotificationScheduleType.FIRST_TRANSIT);
        var normal = notification(3L, NotificationScheduleType.NORMAL);
        var archivedFirst = notification(4L, NotificationScheduleType.FIRST_TRANSIT);
        archivedFirst.archiveTransit();
        var inactiveLast = notification(5L, NotificationScheduleType.LAST_TRANSIT);
        inactiveLast.toggleActive(false);
        when(notifications.findAllByUserId(1L))
                .thenReturn(List.of(last, first, normal, archivedFirst, inactiveLast));
        when(schedules.estimateDeparture(eq(last), any(), any()))
                .thenReturn(LocalDateTime.parse("2026-09-28T00:10:00"));
        when(schedules.estimateDeparture(eq(first), any(), any()))
                .thenReturn(LocalDateTime.parse("2026-09-27T23:45:00"));

        var responses = service.getCurrentNotifications("me");

        assertThat(responses).extracting(TransitNotificationDto.Response::getNotificationId)
                .containsExactly(2L, 1L);
        assertThat(responses.get(0).getScheduleType()).isEqualTo(NotificationScheduleType.FIRST_TRANSIT);
        assertThat(responses.get(0).getEstimatedDepartureAt()).isNull();
        assertThat(responses.get(0).getRemainingSeconds()).isNull();
        assertThat(responses.get(0).getEstimateStatus()).isEqualTo(TransitNotificationDto.EstimateStatus.UNAVAILABLE);
        assertThat(responses.get(0).getEstimateErrorCode()).isEqualTo("T006");
        assertThat(responses.get(1).getScheduleType()).isEqualTo(NotificationScheduleType.LAST_TRANSIT);
        assertThat(responses.get(1).getEstimatedDepartureAt())
                .isEqualTo(OffsetDateTime.parse("2026-09-28T00:10:00+09:00"));
        assertThat(responses.get(1).getServerTime())
                .isEqualTo(OffsetDateTime.parse("2026-09-27T23:50:00+09:00"));
        assertThat(responses.get(1).getRemainingSeconds()).isEqualTo(1200L);
        assertThat(new tools.jackson.databind.ObjectMapper().writeValueAsString(responses))
                .doesNotContain("routeName");
        verify(schedules, never()).estimateDeparture(eq(normal), any(), any());
        verify(schedules, never()).estimateDeparture(eq(archivedFirst), any(), any());
        verify(schedules, never()).estimateDeparture(eq(inactiveLast), any(), any());
    }

    @Test void filtersCurrentNotificationsByRequestedScheduleType() {
        var first = notification(10L, NotificationScheduleType.FIRST_TRANSIT);
        var last = notification(20L, NotificationScheduleType.LAST_TRANSIT);
        when(notifications.findAllByUserId(1L)).thenReturn(List.of(first, last));
        when(schedules.estimateDeparture(eq(first), any(), any()))
                .thenReturn(LocalDateTime.parse("2026-09-28T05:30:00"));
        when(schedules.estimateDeparture(eq(last), any(), any()))
                .thenReturn(LocalDateTime.parse("2026-09-28T00:10:00"));

        var firstResponses = service.getCurrentNotifications(
                "me", NotificationScheduleType.FIRST_TRANSIT);
        var lastResponses = service.getCurrentNotifications(
                "me", NotificationScheduleType.LAST_TRANSIT);

        assertThat(firstResponses).hasSize(1);
        assertThat(firstResponses.get(0).getNotificationId()).isEqualTo(10L);
        assertThat(firstResponses.get(0).getScheduleType())
                .isEqualTo(NotificationScheduleType.FIRST_TRANSIT);

        assertThat(lastResponses).hasSize(1);
        assertThat(lastResponses.get(0).getNotificationId()).isEqualTo(20L);
        assertThat(lastResponses.get(0).getScheduleType())
                .isEqualTo(NotificationScheduleType.LAST_TRANSIT);
    }

    @Test void rejectsNormalScheduleTypeOnTransitListQuery() {
        assertThatThrownBy(() ->
                service.getCurrentNotifications("me", NotificationScheduleType.NORMAL))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        verify(notifications, never()).findAllByUserId(anyLong());
        verifyNoInteractions(schedules);
    }

    @Test void unavailableTimetablePreservesSettingWithoutPretendingZeroRemaining() {
        var missing = notification(1L, NotificationScheduleType.FIRST_TRANSIT);
        when(notifications.findAllByUserId(1L)).thenReturn(List.of(missing));
        when(schedules.estimateDeparture(eq(missing), any(), any()))
                .thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
        var response = service.getCurrentNotifications("me").get(0);
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
        var inactive = notification(1L, NotificationScheduleType.FIRST_TRANSIT);
        inactive.toggleActive(false);
        when(notifications.findById(1L)).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.getNotification("me", 1L)).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
        when(notifications.findById(1L)).thenReturn(Optional.of(notification(1L, NotificationScheduleType.NORMAL)));
        assertThatThrownBy(() -> service.getNotification("me", 1L)).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
        verifyNoInteractions(schedules);
    }

    @Test void absentOrInactiveSettingsReturnEmptyList() {
        var archived = notification(1L, NotificationScheduleType.FIRST_TRANSIT);
        archived.archiveTransit();
        var inactive = notification(2L, NotificationScheduleType.LAST_TRANSIT);
        inactive.toggleActive(false);
        when(notifications.findAllByUserId(1L)).thenReturn(List.of(archived, inactive));

        assertThat(service.getCurrentNotifications("me")).isEmpty();

        var controller = new com.OnETA.controller.ScheduleNotificationController(mock(NotificationService.class), service);
        var json = new tools.jackson.databind.ObjectMapper().writeValueAsString(
                controller.transit(() -> "me", NotificationScheduleType.FIRST_TRANSIT));
        assertThat(json).contains("SUCCESS", "\"data\":[]");
        verifyNoInteractions(schedules);
    }

    private ArrivalNotification notification(Long id, NotificationScheduleType type) {
        var notification = new ArrivalNotification(user, "route", List.of(10), 0, LocalTime.NOON, "{}", type);
        ReflectionTestUtils.setField(notification, "id", id);
        return notification;
    }
}
