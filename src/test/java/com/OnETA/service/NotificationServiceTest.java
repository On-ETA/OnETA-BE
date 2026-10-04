package com.OnETA.service;

import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.NotificationDto;
import com.OnETA.entity.*;
import com.OnETA.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void lockLookup() {
        when(users.findForNotificationByEmail(anyString()))
                .thenAnswer(invocation -> users.findByEmail(invocation.getArgument(0)));
        when(transit.readSavedRoute("{}")).thenReturn(com.OnETA.dto.TransitDto.RouteOptionResponse.builder()
                .segments(List.of(com.OnETA.dto.TransitDto.RouteSegment.builder()
                        .transitType("BUS").transitName("603").build())).build());
    }

    private final ArrivalNotificationRepository arrivals = mock(ArrivalNotificationRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final User user = mock(User.class);
    private final TransitApiService transit = mock(TransitApiService.class);
    private final NotificationService service = new NotificationService(arrivals,
            mock(NotificationRepository.class), users, new RepeatDaysService(), transit, mock(NotificationDeliveryRepository.class));

    @ParameterizedTest
    @EnumSource(value = NotificationScheduleType.class, names = {"FIRST_TRANSIT", "LAST_TRANSIT"})
    void savesKakaoTransitWithoutLiveTimetableValidation(NotificationScheduleType type) {
        var route = com.OnETA.dto.TransitDto.RouteOptionResponse.builder()
                .provider("KAKAO").routeId("KAKAO_test")
                .segments(List.of(com.OnETA.dto.TransitDto.RouteSegment.builder()
                        .transitType("BUS").transitName("273").build()))
                .build();
        when(transit.readSavedRoute("{}")).thenReturn(route);
        when(user.getId()).thenReturn(1L);
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(arrivals.save(any())).thenAnswer(i -> i.getArgument(0));

        // Even if a live timetable check would currently fail, registration must not call it.
        doThrow(new GlobalException(com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED))
                .when(transit).validateSeoulSchedule(any());

        service.createArrivalNotification("test@example.com", request(type));

        verify(transit, never()).validateSeoulSchedule(any());
        verify(arrivals).save(any());

        var notification = new ArrivalNotification(user, "경로", List.of(10), 0,
                LocalTime.of(9, 0), "{}", NotificationScheduleType.NORMAL);
        when(arrivals.findById(1L)).thenReturn(Optional.of(notification));
        var update = new NotificationDto.UpdateArrivalRequest();
        update.setScheduleType(type);

        service.updateArrivalNotification("test@example.com", 1L, update);

        verify(transit, never()).validateSeoulSchedule(any());
        assertThat(notification.getScheduleType()).isEqualTo(type);
        assertThat(notification.getTargetArrivalTime()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = NotificationScheduleType.class, names = {"FIRST_TRANSIT", "LAST_TRANSIT"})
    void rejectsNightOnlyRouteForFirstLastSave(NotificationScheduleType type) {
        var route = com.OnETA.dto.TransitDto.RouteOptionResponse.builder()
                .provider("ODSAY").routeId("NIGHT_test")
                .segments(List.of(
                        com.OnETA.dto.TransitDto.RouteSegment.builder()
                                .transitType("WALK").durationMinutes(3).build(),
                        com.OnETA.dto.TransitDto.RouteSegment.builder()
                                .transitType("BUS").transitName("N62").nightBus(true).durationMinutes(25).build(),
                        com.OnETA.dto.TransitDto.RouteSegment.builder()
                                .transitType("WALK").durationMinutes(3).build()))
                .build();
        when(transit.readSavedRoute("{}")).thenReturn(route);
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.createArrivalNotification("test@example.com", request(type)))
                .isInstanceOfSatisfying(GlobalException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.OnETA.common.error.ErrorCode.TRANSIT_NIGHT_ONLY_ROUTE));
        verify(arrivals, never()).save(any());
    }

    @Test
    void rejectsMalformedTransitRouteBeforeSave() {
        doThrow(new GlobalException(com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE))
                .when(transit).readSavedRoute("bad-route");
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        var request = request(NotificationScheduleType.FIRST_TRANSIT);
        request.setRouteDetails("bad-route");

        assertThatThrownBy(() -> service.createArrivalNotification("test@example.com", request))
                .isInstanceOfSatisfying(GlobalException.class, e -> assertThat(e.getErrorCode())
                        .isEqualTo(com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE));
        verify(arrivals, never()).save(any());
        verify(transit, never()).validateSeoulSchedule(any());
    }

    private NotificationDto.CreateArrivalRequest request(NotificationScheduleType type) {
        NotificationDto.CreateArrivalRequest request = new NotificationDto.CreateArrivalRequest();
        request.setRouteName("테스트 경로");
        request.setRouteDetails("{}");
        request.setReminderOffsetMinutes(List.of(10));
        request.setScheduleType(type);
        return request;
    }

    @ParameterizedTest
    @EnumSource(value = NotificationScheduleType.class, names = {"FIRST_TRANSIT", "LAST_TRANSIT"})
    void createsScheduledTransitWithoutTargetArrivalTime(NotificationScheduleType type) {
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(arrivals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = request(type);
        request.setRepeatDays(List.of("MON", "FRI"));
        service.createArrivalNotification("test@example.com", request);
        ArgumentCaptor<ArrivalNotification> saved = ArgumentCaptor.forClass(ArrivalNotification.class);
        verify(arrivals).save(saved.capture());
        assertThat(saved.getValue().getTargetArrivalTime()).isNull();
        assertThat(saved.getValue().getScheduleType()).isEqualTo(type);
        assertThat(saved.getValue().getRepeatDays()).isZero();
    }

    @Test
    void normalAndDefaultTypeRequireTargetArrivalTime() {
        assertThatThrownBy(() -> service.createArrivalNotification("test@example.com", request(null)))
                .isInstanceOf(GlobalException.class).hasMessageContaining("목표 도착시간");
        assertThatThrownBy(() -> service.createArrivalNotification("test@example.com", request(NotificationScheduleType.NORMAL)))
                .isInstanceOf(GlobalException.class);
        verifyNoInteractions(arrivals);
    }

    @Test
    void normalCreationPreservesArrivalTimeAndDefaultsType() {
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(arrivals.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var request = request(null);
        request.setTargetArrivalTime(LocalTime.of(9, 0));
        request.setRepeatDays(List.of("MON", "FRI"));
        service.createArrivalNotification("test@example.com", request);
        ArgumentCaptor<ArrivalNotification> saved = ArgumentCaptor.forClass(ArrivalNotification.class);
        verify(arrivals).save(saved.capture());
        assertThat(saved.getValue().getScheduleType()).isEqualTo(NotificationScheduleType.NORMAL);
        assertThat(saved.getValue().getTargetArrivalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(saved.getValue().getRepeatDays()).isEqualTo(17);
    }

    @Test
    void switchingTypesClearsUnusedTimeAndRequiresTimeWhenReturningToNormal() {
        when(user.getId()).thenReturn(1L);
        when(users.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        ArrivalNotification notification = new ArrivalNotification(user, "경로", List.of(10), 31,
                LocalTime.of(9, 0), "{}", NotificationScheduleType.NORMAL);
        when(arrivals.findById(1L)).thenReturn(Optional.of(notification));
        var update = new NotificationDto.UpdateArrivalRequest();
        update.setScheduleType(NotificationScheduleType.FIRST_TRANSIT);
        service.updateArrivalNotification("test@example.com", 1L, update);
        assertThat(notification.getTargetArrivalTime()).isNull();
        assertThat(notification.getRepeatDays()).isZero();

        update.setRepeatDays(List.of("MON", "FRI"));
        service.updateArrivalNotification("test@example.com", 1L, update);
        assertThat(notification.getRepeatDays()).isZero();

        update.setScheduleType(NotificationScheduleType.NORMAL);
        assertThatThrownBy(() -> service.updateArrivalNotification("test@example.com", 1L, update))
                .isInstanceOf(GlobalException.class);
        assertThat(notification.getScheduleType()).isEqualTo(NotificationScheduleType.FIRST_TRANSIT);
        update.setTargetArrivalTime(LocalTime.of(10, 0));
        service.updateArrivalNotification("test@example.com", 1L, update);
        assertThat(notification.getTargetArrivalTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(notification.getScheduleType()).isEqualTo(NotificationScheduleType.NORMAL);
        assertThat(notification.getRepeatDays()).isEqualTo(17);
    }
}
