package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.NotificationScheduleType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransitRouteOptimizationServiceTest {

    @Test
    void lastTransitRanksLatestDepartureAcrossOdsayAndKakaoCandidates() {
        TransitApiService api = mock(TransitApiService.class);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var a = route("A", "ODSAY", 30);
        var b = route("B", "KAKAO", 40);
        var c = route("C", "ODSAY", 20);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(5)))
                .thenReturn(List.of(a, b, c));
        when(schedules.previewDepartureForServiceDate(eq(a), eq(NotificationScheduleType.LAST_TRANSIT), any(), anyMap()))
                .thenReturn(LocalDateTime.of(2026, 10, 2, 23, 10));
        when(schedules.previewDepartureForServiceDate(eq(b), eq(NotificationScheduleType.LAST_TRANSIT), any(), anyMap()))
                .thenReturn(LocalDateTime.of(2026, 10, 2, 23, 35));
        when(schedules.previewDepartureForServiceDate(eq(c), eq(NotificationScheduleType.LAST_TRANSIT), any(), anyMap()))
                .thenReturn(LocalDateTime.of(2026, 10, 2, 23, 20));

        var result = service.search("user@test.com", 126.8, 37.5, "출발",
                127.0, 37.6, "도착", NotificationScheduleType.LAST_TRANSIT);

        assertThat(result).extracting(r -> r.getRoute().getRouteId())
                .containsExactly("B", "C", "A");
        assertThat(result.get(0).getRoute().getProvider()).isEqualTo("KAKAO");
    }

    @Test
    void firstTransitRanksEarliestDepartureAndSkipsUnsupportedCandidate() {
        TransitApiService api = mock(TransitApiService.class);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var unsupported = route("X", "KAKAO", 10);
        var early = route("EARLY", "ODSAY", 35);
        var late = route("LATE", "ODSAY", 20);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(5)))
                .thenReturn(List.of(unsupported, late, early));
        when(schedules.previewDepartureForServiceDate(eq(unsupported), eq(NotificationScheduleType.FIRST_TRANSIT), any(), anyMap()))
                .thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
        when(schedules.previewDepartureForServiceDate(eq(early), eq(NotificationScheduleType.FIRST_TRANSIT), any(), anyMap()))
                .thenReturn(LocalDateTime.of(2026, 10, 3, 5, 10));
        when(schedules.previewDepartureForServiceDate(eq(late), eq(NotificationScheduleType.FIRST_TRANSIT), any(), anyMap()))
                .thenReturn(LocalDateTime.of(2026, 10, 3, 5, 30));

        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.FIRST_TRANSIT);

        assertThat(result).extracting(r -> r.getRoute().getRouteId())
                .containsExactly("EARLY", "LATE");
    }

    @Test
    void firstTransitAdvancesWholeServiceDayAfterEarliestFirstHasPassed() {
        TransitApiService api = mock(TransitApiService.class);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var morning = route("MORNING", "ODSAY", 60);
        var night = route("NIGHT", "ODSAY", 90);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(5)))
                .thenReturn(List.of(morning, night));

        when(schedules.previewDepartureForServiceDate(eq(morning), eq(NotificationScheduleType.FIRST_TRANSIT),
                any(LocalDate.class), anyMap())).thenAnswer(invocation -> {
            LocalDate day = invocation.getArgument(2);
            return day.atTime(5, 30);
        });
        when(schedules.previewDepartureForServiceDate(eq(night), eq(NotificationScheduleType.FIRST_TRANSIT),
                any(LocalDate.class), anyMap())).thenAnswer(invocation -> {
            LocalDate day = invocation.getArgument(2);
            return day.atTime(22, 17);
        });

        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock",
                java.time.Clock.fixed(java.time.ZonedDateTime.parse("2026-10-04T15:00:00+09:00").toInstant(),
                        java.time.ZoneId.of("Asia/Seoul")));

        var result = service.search("user@test.com", 127.07, 37.20, null,
                126.92, 37.55, null, NotificationScheduleType.FIRST_TRANSIT);

        assertThat(result.get(0).getRoute().getRouteId()).isEqualTo("MORNING");
        assertThat(result.get(0).getEstimatedDepartureAt().toLocalDate())
                .isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(result.get(0).getEstimatedDepartureAt().toLocalTime())
                .isEqualTo(java.time.LocalTime.of(5, 30));
    }

    @Test
    void allFailedCandidatesPreferTransientUnavailableError() {
        TransitApiService api = mock(TransitApiService.class);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var unsupported = route("X", "KAKAO", 10);
        var unavailable = route("Y", "ODSAY", 10);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(5)))
                .thenReturn(List.of(unsupported, unavailable));
        when(schedules.previewDepartureForServiceDate(eq(unsupported), any(), any(), anyMap()))
                .thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
        when(schedules.previewDepartureForServiceDate(eq(unavailable), any(), any(), anyMap()))
                .thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE));

        assertThatThrownBy(() -> service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE));
    }

    @Test
    void lastTransitDoesNotMixTomorrowsLastWhenAnotherRouteStillRunsToday() {
        TransitApiService api = mock(TransitApiService.class);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var alreadyEnded = route("ENDED", "ODSAY", 30);
        var stillRunning = route("RUNNING", "ODSAY", 30);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(5)))
                .thenReturn(List.of(alreadyEnded, stillRunning));

        when(schedules.previewDepartureForServiceDate(eq(alreadyEnded), eq(NotificationScheduleType.LAST_TRANSIT),
                any(LocalDate.class), anyMap())).thenAnswer(invocation -> {
            LocalDate day = invocation.getArgument(2);
            return day.atTime(23, 10);
        });
        when(schedules.previewDepartureForServiceDate(eq(stillRunning), eq(NotificationScheduleType.LAST_TRANSIT),
                any(LocalDate.class), anyMap())).thenAnswer(invocation -> {
            LocalDate day = invocation.getArgument(2);
            return day.atTime(23, 50);
        });

        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock",
                java.time.Clock.fixed(java.time.ZonedDateTime.parse("2026-10-02T23:30:00+09:00").toInstant(),
                        java.time.ZoneId.of("Asia/Seoul")));

        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);

        assertThat(result).extracting(r -> r.getRoute().getRouteId())
                .containsExactly("RUNNING");
        assertThat(result.get(0).getEstimatedDepartureAt().toLocalDate())
                .isEqualTo(LocalDate.of(2026, 10, 2));
    }

    private TransitDto.RouteOptionResponse route(String id, String provider, int duration) {
        return TransitDto.RouteOptionResponse.builder()
                .routeId(id).provider(provider).totalDurationMinutes(duration)
                .segments(List.of(TransitDto.RouteSegment.builder()
                        .transitType("SUBWAY").durationMinutes(duration)
                        .startStation("A").endStation("B").build()))
                .build();
    }
}
