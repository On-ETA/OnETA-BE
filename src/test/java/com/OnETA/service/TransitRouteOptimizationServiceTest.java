package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.FirstLastRouteStatus;
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
        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock",
                java.time.Clock.fixed(java.time.ZonedDateTime.parse("2026-10-02T20:00:00+09:00").toInstant(),
                        java.time.ZoneId.of("Asia/Seoul")));
        var a = route("A", "ODSAY", 30);
        var b = route("B", "KAKAO", 40);
        var c = route("C", "ODSAY", 20);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE)))
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
        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock",
                java.time.Clock.fixed(java.time.ZonedDateTime.parse("2026-10-03T04:00:00+09:00").toInstant(),
                        java.time.ZoneId.of("Asia/Seoul")));
        var unsupported = route("X", "KAKAO", 10);
        var early = route("EARLY", "ODSAY", 35);
        var late = route("LATE", "ODSAY", 20);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE)))
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
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE)))
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
    void firstSearchReturnsNOnlyPathAsInformationalEvenWithoutTimetable() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var night = nightRoute("NIGHT", "N62", 35);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(night));
        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.FIRST_TRANSIT);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(FirstLastRouteStatus.NIGHT_ONLY);
        assertThat(result.get(0).getEstimatedDepartureAt()).isNull();
        assertThat(result.get(0).getRoute().getSelectedDepartureAt()).isNull();
        verifyNoInteractions(schedules);
    }

    @Test
    void midnightRanksActiveNightAndOrdinaryBusesWithoutMixingTonight() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var now = LocalDateTime.of(2026, 10, 7, 0, 2);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock",
                java.time.Clock.fixed(now.atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                        java.time.ZoneId.of("Asia/Seoul")));
        var ended = route("ENDED", "KAKAO", 20);
        var ordinary = route("ORDINARY", "KAKAO", 25);
        var night = nightRoute("NIGHT", "N62", 35);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(ended, ordinary, night));
        when(schedules.previewCurrentLastDeparture(eq(ordinary), eq(now), anyMap()))
                .thenReturn(now.toLocalDate().atTime(0, 20));
        when(schedules.previewCurrentLastDeparture(eq(night), eq(now), anyMap()))
                .thenReturn(now.toLocalDate().atTime(3, 10));
        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);
        assertThat(result).extracting(r -> r.getRoute().getRouteId()).containsExactly("ORDINARY", "NIGHT");
        assertThat(result).extracting(TransitDto.FirstLastRouteOptionResponse::getStatus)
                .containsExactly(FirstLastRouteStatus.AVAILABLE, FirstLastRouteStatus.NIGHT_ONLY);
        assertThat(result.get(0).getEstimatedDepartureAt().toLocalDate()).isEqualTo(now.toLocalDate());
        assertThat(result.get(1).getEstimatedDepartureAt()).isNull();
        verify(schedules, never()).previewCurrentLastDeparture(eq(night), any(), anyMap());
        verify(schedules, never()).previewDepartureForServiceDate(any(), any(), any(), anyMap());
    }

    @Test
    void sixthProviderNightBusIsStillShownButNeverAsAvailable() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        setClock(service, LocalDateTime.of(2026, 10, 7, 0, 2));
        var routes = new java.util.ArrayList<TransitDto.RouteOptionResponse>();
        for (int i = 0; i < 5; i++) routes.add(route("DAY" + i, "ODSAY", 20));
        var night = nightRoute("NIGHT_SIXTH", "N62", 35);
        routes.add(night);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(routes);
        when(schedules.previewCurrentLastDeparture(eq(night), any(), anyMap()))
                .thenAnswer(i -> ((LocalDateTime) i.getArgument(1)).plusHours(2));
        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);
        assertThat(result).extracting(r -> r.getRoute().getRouteId()).containsExactly("NIGHT_SIXTH");
        assertThat(result.get(0).getStatus()).isEqualTo(FirstLastRouteStatus.NIGHT_ONLY);
        assertThat(result.get(0).getEstimatedDepartureAt()).isNull();
        verify(schedules, times(5)).previewCurrentLastDeparture(any(), any(), anyMap());
        verify(schedules, never()).previewCurrentLastDeparture(eq(night), any(), anyMap());
    }

    @Test
    void currentWindowOutageDoesNotSilentlyJumpToTonightsLast() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        setClock(service, LocalDateTime.of(2026, 10, 9, 2, 3));
        var route = route("BUS", "KAKAO", 20);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(route));
        when(schedules.previewCurrentLastDeparture(eq(route), any(), anyMap()))
                .thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE));
        assertThatThrownBy(() -> service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE));
        verify(schedules, never()).previewDepartureForServiceDate(any(), any(), any(), anyMap());
    }

    @Test
    void noNightRouteDoesNotReserveAResultSlot() {
        TransitApiService api = mock(TransitApiService.class);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock",
                java.time.Clock.fixed(java.time.ZonedDateTime.parse("2026-10-02T20:00:00+09:00").toInstant(),
                        java.time.ZoneId.of("Asia/Seoul")));

        var a = route("A", "ODSAY", 30);
        var b = route("B", "ODSAY", 25);
        var c = route("C", "KAKAO", 20);

        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(a, b, c));
        when(schedules.previewDepartureForServiceDate(eq(a), eq(NotificationScheduleType.LAST_TRANSIT), any(), anyMap()))
                .thenReturn(LocalDateTime.of(2026, 10, 2, 23, 10));
        when(schedules.previewDepartureForServiceDate(eq(b), eq(NotificationScheduleType.LAST_TRANSIT), any(), anyMap()))
                .thenReturn(LocalDateTime.of(2026, 10, 2, 23, 20));
        when(schedules.previewDepartureForServiceDate(eq(c), eq(NotificationScheduleType.LAST_TRANSIT), any(), anyMap()))
                .thenReturn(LocalDateTime.of(2026, 10, 2, 23, 30));

        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(r -> r.getRoute().getRouteId())
                .containsExactly("C", "B", "A");
    }

    @Test
    void allFailedCandidatesPreferTransientUnavailableError() {
        TransitApiService api = mock(TransitApiService.class);
        TransitScheduleService schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var now = LocalDateTime.of(2026, 10, 9, 2, 3);
        setClock(service, now);
        var unsupported = route("X", "KAKAO", 10);
        var unavailable = route("Y", "ODSAY", 10);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(unsupported, unavailable));
        when(schedules.previewCurrentLastDeparture(eq(unsupported), eq(now), anyMap()))
                .thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
        when(schedules.previewCurrentLastDeparture(eq(unavailable), eq(now), anyMap()))
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
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(), anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE)))
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

    @Test
    void firstSearchIncludesThreeAndNineButExcludesTimesOutsideWindow() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var day = LocalDate.of(2026, 10, 8);
        setClock(service, day.atTime(0, 3));
        var times = List.of(day.atTime(2, 59), day.atTime(3, 0), day.atTime(8, 59),
                day.atTime(9, 0), day.atTime(9, 1), day.atTime(19, 27));
        var routes = new java.util.ArrayList<TransitDto.RouteOptionResponse>();
        for (int i = 0; i < times.size(); i++) {
            var route = route("R" + i, "ODSAY", 30);
            routes.add(route);
            when(schedules.previewDepartureForServiceDate(eq(route), eq(NotificationScheduleType.FIRST_TRANSIT),
                    eq(day), anyMap())).thenReturn(times.get(i));
        }
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(routes);
        assertThat(service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.FIRST_TRANSIT))
                .extracting(r -> r.getRoute().getRouteId()).containsExactly("R1", "R2", "R3");
    }

    @Test
    void lastSearchDoesNotExposeNextOperatingDaysLastAsTodaysLast() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var day = LocalDate.of(2026, 10, 8);
        setClock(service, day.atTime(20, 0));
        var route = route("R", "ODSAY", 30);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(route));
        when(schedules.previewDepartureForServiceDate(eq(route), eq(NotificationScheduleType.LAST_TRANSIT),
                eq(day), anyMap())).thenReturn(day.atTime(20, 30));
        when(schedules.previewDepartureForServiceDate(eq(route), eq(NotificationScheduleType.LAST_TRANSIT),
                eq(day.plusDays(1)), anyMap())).thenReturn(day.plusDays(1).atTime(21, 0));
        assertThatThrownBy(() -> service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_ROUTE_NOT_FOUND));
        verify(schedules, never()).previewDepartureForServiceDate(eq(route),
                eq(NotificationScheduleType.LAST_TRANSIT), eq(day.plusDays(1)), anyMap());
    }

    @Test
    void lastAt2359OnlyReturnsTheStillCatchableOvernightTrip() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var now = LocalDateTime.of(2026, 10, 8, 23, 59);
        setClock(service, now);
        var current = route("MIDNIGHT", "KAKAO", 32);
        var tomorrow = route("TOMORROW", "KAKAO", 30);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(current, tomorrow));
        when(schedules.previewCurrentLastDeparture(eq(current), eq(now), anyMap()))
                .thenReturn(now.toLocalDate().plusDays(1).atTime(0, 10));
        when(schedules.previewCurrentLastDeparture(eq(tomorrow), eq(now), anyMap()))
                .thenReturn(now.toLocalDate().plusDays(1).atTime(23, 0));

        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRoute().getRouteId()).isEqualTo("MIDNIGHT");
        assertThat(result.get(0).getEstimatedDepartureAt())
                .isEqualTo(result.get(0).getRoute().getSelectedDepartureAt());
        assertThat(result.get(0).getEstimatedDepartureAt().toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 10, 9, 0, 10));
    }

    @Test
    void lastAt0001DoesNotJumpToTonightWhenCurrentLastUnavailable() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var now = LocalDateTime.of(2026, 10, 9, 0, 1);
        setClock(service, now);
        var tomorrowNight = route("NIGHT", "KAKAO", 32);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(tomorrowNight));
        when(schedules.previewCurrentLastDeparture(eq(tomorrowNight), eq(now), anyMap()))
                .thenReturn(now.toLocalDate().atTime(23, 0));

        assertThatThrownBy(() -> service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_ROUTE_NOT_FOUND));
        verify(schedules, never()).previewDepartureForServiceDate(any(), any(), any(), anyMap());
    }

    @Test
    void activeLastSearchIncludesSixButNotTonightAtMidnight() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var day = LocalDate.of(2026, 10, 8);
        var now = day.atTime(0, 3);
        setClock(service, now);
        var times = List.of(day.atTime(5, 59), day.atTime(6, 0), day.atTime(6, 1),
                day.atTime(19, 27), day.atTime(20, 59), day.atTime(21, 0), day.atTime(23, 59));
        var routes = new java.util.ArrayList<TransitDto.RouteOptionResponse>();
        for (int i = 0; i < times.size(); i++) {
            var route = route("R" + i, "ODSAY", 30);
            routes.add(route);
            when(schedules.previewCurrentLastDeparture(eq(route), eq(now), anyMap())).thenReturn(times.get(i));
        }
        var unknownNight = nightRoute("UNKNOWN", "N62", 35);
        routes.add(unknownNight);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(routes);
        assertThat(service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT))
                .extracting(r -> r.getRoute().getRouteId()).containsExactly("R1", "R0", "UNKNOWN");
    }

    @Test
    void midnightUnverifiedDirectNightRouteIsShownAsNightOnlyInsteadOfT005() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var now = LocalDateTime.of(2026, 10, 9, 2, 3);
        setClock(service, now);
        var night = nightRoute("N62_ONLY", "N62", 35);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(night));
        when(schedules.previewCurrentLastDeparture(eq(night), eq(now), anyMap()))
                .thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));

        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRoute().getRouteId()).isEqualTo("N62_ONLY");
        assertThat(result.get(0).getStatus()).isEqualTo(FirstLastRouteStatus.NIGHT_ONLY);
        assertThat(result.get(0).getEstimatedDepartureAt()).isNull();
        verify(schedules, never()).previewDepartureForServiceDate(any(), any(), any(), anyMap());
    }

    @Test
    void midnightKnownEndedNightRouteRemainsInformationalOnly() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var now = LocalDateTime.of(2026, 10, 9, 2, 3);
        setClock(service, now);
        var night = nightRoute("ENDED_N62", "N62", 35);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(night));
        // null means the interval is known not to contain a catchable last departure.
        when(schedules.previewCurrentLastDeparture(eq(night), eq(now), anyMap()))
                .thenReturn(null);

        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(FirstLastRouteStatus.NIGHT_ONLY);
        assertThat(result.get(0).getEstimatedDepartureAt()).isNull();
        verify(schedules, never()).previewCurrentLastDeparture(eq(night), any(), anyMap());
    }

    @Test
    void n51WithCatchableLastTrainLikeTimeStillReturnsNightOnlyNullDeparture() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var now = LocalDateTime.of(2026, 10, 9, 23, 51);
        setClock(service, now);
        var night = nightRoute("SEOUL_NIGHT_N51", "N51", 34).toBuilder()
                .provider("SEOUL_NIGHT")
                .selectedDepartureAt(java.time.OffsetDateTime.parse("2026-10-10T02:31:00+09:00"))
                .build();
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(night));

        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRoute().getRouteId()).isEqualTo("SEOUL_NIGHT_N51");
        assertThat(result.get(0).getStatus()).isEqualTo(FirstLastRouteStatus.NIGHT_ONLY);
        assertThat(result.get(0).getEstimatedDepartureAt()).isNull();
        assertThat(result.get(0).getRoute().getSelectedDepartureAt()).isNull();
        verifyNoInteractions(schedules);
    }

    @Test
    void mixedNightAndOrdinaryTransitIsNotAutomaticallyNightOnly() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var now = LocalDateTime.of(2026, 10, 9, 23, 0);
        setClock(service, now);
        var night = nightRoute("MIXED", "N51", 34);
        var mixed = night.toBuilder()
                .segments(java.util.stream.Stream.concat(night.getSegments().stream(),
                        java.util.stream.Stream.of(TransitDto.RouteSegment.builder()
                                .transitType("SUBWAY").transitName("2호선").durationMinutes(8).build()))
                        .toList()).build();
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE))).thenReturn(List.of(mixed));
        when(schedules.previewCurrentLastDeparture(eq(mixed), eq(now), anyMap()))
                .thenReturn(now.plusMinutes(20));

        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(FirstLastRouteStatus.AVAILABLE);
    }

    @Test
    void midnightAvailableRouteRanksBeforeUnverifiedNightOnlyRoute() {
        var api = mock(TransitApiService.class);
        var schedules = mock(TransitScheduleService.class);
        var service = new TransitRouteOptimizationService(api, schedules);
        var now = LocalDateTime.of(2026, 10, 9, 2, 3);
        setClock(service, now);
        var available = route("AVAILABLE", "KAKAO", 25);
        var night = nightRoute("N62_ONLY", "N62", 35);
        when(api.searchScheduleCandidates(anyString(), anyDouble(), anyDouble(), any(),
                anyDouble(), anyDouble(), any(), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(night, available));
        when(schedules.previewCurrentLastDeparture(eq(night), eq(now), anyMap()))
                .thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
        when(schedules.previewCurrentLastDeparture(eq(available), eq(now), anyMap()))
                .thenReturn(now.plusMinutes(20));

        var result = service.search("user@test.com", 126.8, 37.5, null,
                127.0, 37.6, null, NotificationScheduleType.LAST_TRANSIT);

        assertThat(result).extracting(r -> r.getRoute().getRouteId())
                .containsExactly("AVAILABLE", "N62_ONLY");
        assertThat(result).extracting(TransitDto.FirstLastRouteOptionResponse::getStatus)
                .containsExactly(FirstLastRouteStatus.AVAILABLE, FirstLastRouteStatus.NIGHT_ONLY);
        assertThat(result.get(0).getEstimatedDepartureAt()).isNotNull();
        assertThat(result.get(1).getEstimatedDepartureAt()).isNull();
    }

    private void setClock(TransitRouteOptimizationService service, LocalDateTime now) {
        var zone = java.time.ZoneId.of("Asia/Seoul");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "clock",
                java.time.Clock.fixed(now.atZone(zone).toInstant(), zone));
    }

    private TransitDto.RouteOptionResponse route(String id, String provider, int duration) {
        return TransitDto.RouteOptionResponse.builder()
                .routeId(id).provider(provider).totalDurationMinutes(duration)
                .segments(List.of(TransitDto.RouteSegment.builder()
                        .transitType("SUBWAY").durationMinutes(duration)
                        .startStation("A").endStation("B").build()))
                .build();
    }

    private TransitDto.RouteOptionResponse nightRoute(String id, String busName, int duration) {
        return TransitDto.RouteOptionResponse.builder()
                .routeId(id).provider("ODSAY").totalDurationMinutes(duration)
                .segments(List.of(
                        TransitDto.RouteSegment.builder()
                                .transitType("WALK").durationMinutes(3).build(),
                        TransitDto.RouteSegment.builder()
                                .transitType("BUS").transitName(busName).nightBus(true)
                                .durationMinutes(duration - 6).build(),
                        TransitDto.RouteSegment.builder()
                                .transitType("WALK").durationMinutes(3).build()))
                .build();
    }
}
