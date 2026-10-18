package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import com.OnETA.repository.ScheduleSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NormalOperatingHoursTest {
    private final TransitApiService transit = mock(TransitApiService.class);
    private final SeoulBusScheduleService buses = mock(SeoulBusScheduleService.class);
    private final TagoSubwayScheduleService subway = mock(TagoSubwayScheduleService.class);
    private final LocalDate day = LocalDate.of(2026, 10, 14);
    private final RestTemplate http = new RestTemplate();
    private final TransitScheduleService service = new TransitScheduleService(transit,
            mock(PublicDataTransitService.class), mock(ScheduleSnapshotRepository.class),
            new ObjectMapper(), http);

    NormalOperatingHoursTest() {
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(
                day.atTime(12, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", buses);
        service.setTagoSubwayScheduleService(subway);
        when(buses.today()).thenReturn(day);
    }

    @ParameterizedTest
    @CsvSource({"23:25,true", "23:26,false", "05:25,true", "05:24,false"})
    void ordinaryBusChecksBoardingAtBothBoundaries(String arrival, boolean allowed) {
        route("KAKAO", leg("BUS", "603", 20), leg("WALK", "", 5));
        when(buses.resolve(any(), any())).thenReturn(schedule(day.atTime(5, 0), day.atTime(23, 0)));
        check(arrival, allowed, ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS);
    }

    @ParameterizedTest
    @CsvSource({"13:00,false", "00:25,true", "06:00,true"})
    void nightBusCannotBeRegisteredForDaytimeButMayArriveAfterLastBus(String arrival, boolean allowed) {
        route("KAKAO", leg("BUS", "N51(심야)", 70), leg("WALK", "", 5));
        when(buses.resolve(any(), any())).thenReturn(schedule(day.atTime(23, 0), day.plusDays(1).atTime(5, 0)));
        check(arrival, allowed, ErrorCode.TRANSIT_NIGHT_BUS_OUTSIDE_HOURS);
    }

    @Test
    void keepsKakaoProviderWhenCheckingNightBusHours() {
        route("KAKAO", leg("BUS", "N51", 20));
        when(buses.resolve(any(), any())).thenReturn(
                schedule(day.atTime(23, 0), day.plusDays(1).atTime(5, 0)));

        check("00:25", true, ErrorCode.TRANSIT_NIGHT_BUS_OUTSIDE_HOURS);

        var captor = org.mockito.ArgumentCaptor.forClass(TransitDto.RouteOptionResponse.class);
        verify(buses).resolve(captor.capture(), eq(day));
        assertThat(captor.getValue().getProvider()).isEqualTo("KAKAO");
    }

    @Test
    void rejectsClosedSubwayTransferEvenWhenNightBusIsRunning() {
        route("KAKAO", leg("BUS", "N51", 20), leg("WALK", "", 5), leg("SUBWAY", "2호선", 20));
        when(buses.resolve(any(), any())).thenReturn(schedule(day.atTime(23, 0), day.plusDays(1).atTime(5, 0)));
        when(subway.resolve(any(), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);
            return schedule(date.atTime(5, 30), date.atTime(23, 0));
        });
        check("02:00", false, ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS);
    }

    @Test
    void afterMidnightUsesPreviousServiceDaySubwayTimetable() {
        route("KAKAO", leg("SUBWAY", "2호선", 20));
        when(subway.resolve(any(), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);
            return schedule(date.atTime(5, 30), date.plusDays(1).atTime(0, 30));
        });
        check("00:40", true, ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS);
        verify(subway).resolve(any(), eq(day));
        verify(subway).resolve(any(), eq(day.plusDays(1)));
    }

    @Test
    void selectedWeekendDayCannotUseWeekdaySubwayHours() {
        route("KAKAO", leg("SUBWAY", "2호선", 20));
        when(subway.resolve(any(), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);
            int closingHour = date.getDayOfWeek() == DayOfWeek.SATURDAY ? 22 : 23;
            return schedule(date.atTime(5, 30), date.atTime(closingHour, 0));
        });
        assertThatThrownBy(() -> service.validateNormalOperatingTime("details", LocalTime.of(22, 50),
                (1 << 2) | (1 << 5))).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS));
    }

    @Test
    void odsayBusResolvesRealLineEvenWhenClientOmitsNightFlagAndName() {
        var bus = leg("BUS", "", 20).toBuilder().odsayStartStationId("100")
                .odsayRouteId("200").build();
        route("ODSAY", bus);
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(service, "scheduleBaseUrl", "https://example.test");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/busStationInfo")))
                .andRespond(withSuccess("""
                        {"result":{"lane":[{"busID":"200","busFirstTime":"2300","busLastTime":"0500"}]}}
                        """, MediaType.APPLICATION_JSON));
        check("13:00", false, ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS);
        server.verify();
    }

    @Test
    void missingTimetableAndMalformedSegmentsFailClosed() {
        route("KAKAO", leg("BUS", "603", 20));
        when(buses.resolve(any(), any())).thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE));
        check("13:00", false, ErrorCode.TRANSIT_NORMAL_HOURS_UNVERIFIED);
        route("KAKAO", leg("BUS", "603", 20).toBuilder().durationMinutes(null).build());
        check("13:00", false, ErrorCode.TRANSIT_NORMAL_HOURS_UNVERIFIED);
    }

    @ParameterizedTest
    @CsvSource({"신분당선", "수인분당선", "인천1호선"})
    void odsayRouteWithoutProviderIdsCanUseVerifiedPublicTimetable(String line) {
        route("ODSAY", leg("SUBWAY", line, 5));
        when(subway.resolve(any(), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);
            return schedule(date.atTime(5, 30), date.atTime(23, 30));
        });

        check("15:00", true, ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS);
        check("03:00", false, ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS);
    }

    @Test
    void odsayLookupFailureFallsBackToPublicTimetable() {
        route("ODSAY", leg("SUBWAY", "신분당선", 5).toBuilder()
                .odsayStartStationId("100").odsayEndStationId("200").build());
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(service, "scheduleBaseUrl", "https://example.test");
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/subwayPathSchedule")))
                .andRespond(withSuccess("{\"error\":{\"code\":\"500\",\"message\":\"unavailable\"}}",
                        MediaType.APPLICATION_JSON));
        when(subway.resolve(any(), eq(day))).thenReturn(
                schedule(day.atTime(5, 30), day.atTime(23, 30)));

        check("15:00", true, ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS);
        verify(subway).resolve(any(), eq(day));
        server.verify();
    }

    @Test
    void successfulOdsayHoursDoNotNeedPublicFallback() {
        route("ODSAY", leg("SUBWAY", "수인분당선", 5).toBuilder()
                .odsayStartStationId("100").odsayEndStationId("200").build());
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(service, "scheduleBaseUrl", "https://example.test");
        var server = MockRestServiceServer.bindTo(http).build();
        for (String departure : List.of("0530", "2330")) {
            server.expect(requestTo(org.hamcrest.Matchers.containsString("/subwayPathSchedule")))
                    .andRespond(withSuccess("{\"result\":{\"path\":[{\"info\":{\"departureTime\":\""
                            + departure + "\"}}]}}", MediaType.APPLICATION_JSON));
        }

        check("15:00", true, ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS);
        verifyNoInteractions(subway);
        server.verify();
    }

    @Test
    void missingPublicTimetableStillRejectsRegistration() {
        route("ODSAY", leg("SUBWAY", "신분당선", 5));
        when(subway.resolve(any(), any())).thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
        var metro = mock(SeoulMetroTrainScheduleService.class);
        service.setSeoulMetroTrainScheduleService(metro);
        when(metro.resolve(any(), any())).thenThrow(new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));

        check("15:00", false, ErrorCode.TRANSIT_NORMAL_HOURS_UNVERIFIED);
    }

    private void route(String provider, TransitDto.RouteSegment... legs) {
        when(transit.readSavedRoute("details")).thenReturn(TransitDto.RouteOptionResponse.builder()
                .provider(provider).segments(List.of(legs)).build());
    }

    private TransitDto.RouteSegment leg(String type, String name, int minutes) {
        return TransitDto.RouteSegment.builder().transitType(type).transitName(name)
                .durationMinutes(minutes).build();
    }

    private SeoulBusScheduleService.Schedule schedule(LocalDateTime first, LocalDateTime last) {
        return new SeoulBusScheduleService.Schedule("stop", "ars", "line", first, last);
    }

    private void check(String arrival, boolean allowed, ErrorCode error) {
        if (allowed) {
            assertThatCode(() -> service.validateNormalOperatingTime("details", LocalTime.parse(arrival), 0))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> service.validateNormalOperatingTime("details", LocalTime.parse(arrival), 0))
                    .isInstanceOfSatisfying(GlobalException.class, e -> assertThat(e.getErrorCode()).isEqualTo(error));
        }
    }
}
