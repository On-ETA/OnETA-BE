package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import com.OnETA.entity.*;
import com.OnETA.repository.ScheduleSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TransitScheduleServiceTest {
    private static final LocalDate DATE = LocalDate.of(2026, 8, 27);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void calculatesFirstUsingMaxCandidateAndOffset() throws Exception {
        TransitScheduleService service = service("05:30", "23:30");
        TransitDto.RouteOptionResponse route = route(10, 20, "1");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(notification(NotificationScheduleType.FIRST_TRANSIT, 10),
                DATE, DATE.atTime(4, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(5, 10));
    }

    @Test
    void calculatesFirstUsingMaxOfMultipleReminderOffsets() {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, List.of(5, 15, 30)),
                DATE, DATE.atTime(4, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(4, 50));
    }

    @Test
    void calculatesLastUsingMinCandidateAndOffset() throws Exception {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(notification(NotificationScheduleType.LAST_TRANSIT, 10),
                DATE, DATE.atTime(20, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(23, 20));
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(23, 10));
    }

    @Test
    void firstRecoveryIsOneCandidateAndUsesNextBoardingDeadline() throws Exception {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(publicData(service).findArrival(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate("1000", "station", "route", "ars", 480, "TEST"));

        TransitScheduleService.Decision decision = service.evaluate(notification(NotificationScheduleType.FIRST_TRANSIT, 10),
                DATE, DATE.atTime(5, 32), SEOUL);

        assertThat(decision.recovery()).isTrue();
        assertThat(decision.scheduledAt()).isEqualTo(DATE.atTime(5, 20));
        assertThat(decision.hardDeadlineAt()).isEqualTo(DATE.atTime(5, 40));
        verify(publicData(service), times(1)).findArrival(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void recoveryTimeoutCanBeRetriedBeforeFiveMinuteDeadline() throws Exception {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        AtomicReference<ScheduleSnapshot> saved = new AtomicReference<>();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(snapshotRepo(service).save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(publicData(service).findArrival(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("timeout"))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate("1000", "station", "route", "ars", 480, "TEST"));

        ArrivalNotification notification = notification(NotificationScheduleType.FIRST_TRANSIT, 10);
        assertThat(service.evaluate(notification, DATE,
                DATE.atTime(5, 32), SEOUL)).isNull();
        TransitScheduleService.Decision retry = service.evaluate(notification, DATE,
                DATE.atTime(5, 34), SEOUL);

        assertThat(retry.recovery()).isTrue();
        verify(publicData(service), times(2)).findArrival(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reusesSameDaySnapshotWithoutCallingBaseApiAgain() {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        AtomicReference<ScheduleSnapshot> saved = new AtomicReference<>();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(snapshotRepo(service).save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0)); return invocation.getArgument(0);
        });
        ArrivalNotification n = notification(NotificationScheduleType.LAST_TRANSIT, 10);

        service.evaluate(n, DATE, DATE.atTime(20, 0), SEOUL);
        service.evaluate(n, DATE, DATE.atTime(20, 1), SEOUL);

        verify(serviceApi(service), times(1)).readSavedRoute("route");
    }

    @Test
    void firstSafetyIsMonotonicAndDoesNotMoveBackOnLateObservation() {
        TransitScheduleService service = service("05:30", "23:30");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        AtomicReference<ScheduleSnapshot> saved = new AtomicReference<>();
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(snapshotRepo(service).save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0)); return invocation.getArgument(0);
        });
        ArrivalNotification n = notification(NotificationScheduleType.FIRST_TRANSIT, 10);
        when(publicData(service).findArrival(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate("1000", "station", "route", "ars", 60, "TEST"))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate("1000", "station", "route", "ars", 600, "TEST"));

        TransitScheduleService.Decision early = service.evaluate(n, DATE, DATE.atTime(4, 40), SEOUL);
        TransitScheduleService.Decision late = service.evaluate(n, DATE, DATE.atTime(4, 41), SEOUL);

        assertThat(early.scheduledAt()).isEqualTo(DATE.atTime(5, 3));
        assertThat(late.scheduledAt()).isEqualTo(DATE.atTime(5, 3));
    }

    @Test
    void matchesOfficialBusLocalBlIdWhenOdsayBusIdDoesNotMatch() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[{\"busID\":999,\"busLocalBlID\":\"100100088\",\"busNo\":\"603\",\"busFirstTime\":\"0530\",\"busLastTime\":\"2330\"}]}}");
        TransitDto.RouteOptionResponse route = TransitDto.RouteOptionResponse.builder().totalDurationMinutes(20)
                .segments(List.of(TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(10).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS").durationMinutes(10)
                        .odsayStartStationId("193778").odsayRouteId("1168")
                        .localRouteId("100100088").transitName("603").build())).build();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10), DATE, DATE.atTime(4, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
    }

    @Test
    void usesBusLastTimeForLastTransit() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[{\"busID\":1168,\"busLocalBlID\":\"100100088\",\"busNo\":\"603\",\"busFirstTime\":\"05:30\",\"busLastTime\":\"23:30\"}]}}");
        TransitDto.RouteOptionResponse route = TransitDto.RouteOptionResponse.builder().totalDurationMinutes(20)
                .segments(List.of(TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(10).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS").durationMinutes(10)
                        .odsayStartStationId("193778").odsayRouteId("1168")
                        .localRouteId("100100088").transitName("603").build())).build();
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route);
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.LAST_TRANSIT, 10), DATE, DATE.atTime(20, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(23, 20));
    }

    @Test
    void parsesBusTimesAtOrAfterMidnightWithServiceDateRollover() {
        TransitScheduleService service = service("04:30", "23:40");

        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "23:40", DATE))
                .isEqualTo(DATE.atTime(23, 40));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "24:00", DATE))
                .isEqualTo(DATE.plusDays(1).atStartOfDay());
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "24:36", DATE))
                .isEqualTo(DATE.plusDays(1).atTime(0, 36));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "25:15", DATE))
                .isEqualTo(DATE.plusDays(1).atTime(1, 15));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "27:35", DATE))
                .isEqualTo(DATE.plusDays(1).atTime(3, 35));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "28:10", DATE))
                .isEqualTo(DATE.plusDays(1).atTime(4, 10));
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", null, DATE)).isNull();
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "", DATE)).isNull();
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "abc", DATE)).isNull();
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "29:99", DATE)).isNull();
        assertThat((LocalDateTime) ReflectionTestUtils.invokeMethod(service, "parseTime", "-1:00", DATE)).isNull();
    }

    @Test
    void keepsFourDigitOdsayTimesAndCalculatesLastCandidateAcrossDateRollover() {
        TransitScheduleService service = service("04:30", "2515");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.LAST_TRANSIT, 10),
                DATE, DATE.atTime(20, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.plusDays(1).atTime(1, 5));
        assertThat(decision.scheduledAt()).isEqualTo(DATE.plusDays(1).atTime(0, 55));
    }

    @Test
    void preservesDateRolloverForFirstTransitCandidates() {
        TransitScheduleService service = service("2515", "23:40");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10),
                DATE, DATE.atTime(20, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.plusDays(1).atTime(1, 5));
    }

    @Test
    void treatsOdsayErrorArrayAsApiErrorInsteadOfEmptyLanes() {
        TransitScheduleService service = serviceWithResponse(
                "{\"error\":[{\"code\":\"500\",\"message\":\"[ApiKeyAuthFailed] ApiKey authentication failed.\"}]}" );
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1168"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10), DATE, DATE.atTime(4, 0), SEOUL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ODsay schedule API error")
                .hasMessageContaining("ApiKeyAuthFailed");
        verify(snapshotRepo(service), never()).save(any());
    }

    @Test
    void reportsOnlyWhetherOdsayApiKeyIsConfigured() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[]}}" );

        ReflectionTestUtils.setField(service, "apiKey", "");
        assertThat(service.isApiKeyConfigured()).isFalse();

        ReflectionTestUtils.setField(service, "apiKey", "configured-test-value");
        assertThat(service.isApiKeyConfigured()).isTrue();
    }

    @Test
    void trimsApiKeyBeforeScheduleRequest() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[{\"busID\":1168,\"busLocalBlID\":\"100100088\",\"busFirstTime\":\"0530\",\"busLastTime\":\"2330\"}]}}",
                "test-key");
        ReflectionTestUtils.setField(service, "apiKey", "  test-key  ");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1168"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        TransitScheduleService.Decision decision = service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10), DATE, DATE.atTime(4, 0), SEOUL);

        assertThat(decision.baseDepartureAt()).isEqualTo(DATE.atTime(5, 20));
    }

    @Test
    void sendsEmptyApiKeyForBlankValueWithoutThrowingDuringNormalization() {
        TransitScheduleService service = serviceWithResponse(
                "{\"result\":{\"lane\":[{\"busID\":1168,\"busLocalBlID\":\"100100088\",\"busFirstTime\":\"0530\",\"busLastTime\":\"2330\"}]}}",
                "");
        ReflectionTestUtils.setField(service, "apiKey", "  ");
        when(serviceApi(service).readSavedRoute("route")).thenReturn(route(10, 20, "1168"));
        when(snapshotRepo(service).findForUpdate(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.isApiKeyConfigured()).isFalse();
        assertThat(service.evaluate(
                notification(NotificationScheduleType.FIRST_TRANSIT, 10), DATE, DATE.atTime(4, 0), SEOUL))
                .isNotNull();
    }

    private TransitScheduleService serviceWithResponse(String response) {
        return serviceWithResponse(response, "test");
    }

    private TransitScheduleService serviceWithResponse(String response, String expectedApiKey) {
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        ScheduleSnapshotRepository snapshots = mock(ScheduleSnapshotRepository.class);
        TransitApiService transit = mock(TransitApiService.class);
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("busStationInfo")))
                .andExpect(queryParam("apiKey", expectedApiKey))
                .andExpect(header("Referer", "http://localhost:8080/"))
                .andExpect(header("Origin", "http://localhost:8080"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        TransitScheduleService result = new TransitScheduleService(transit, publicData, snapshots, new ObjectMapper(), rest);
        ReflectionTestUtils.setField(result, "apiKey", "test");
        ReflectionTestUtils.setField(result, "scheduleBaseUrl", "http://odsay/v1/api");
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SERVICES.put(result, new Deps(transit, publicData, snapshots));
        return result;
    }

    private TransitScheduleService service(String first, String last) {
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        ScheduleSnapshotRepository snapshots = mock(ScheduleSnapshotRepository.class);
        TransitApiService transit = mock(TransitApiService.class);
        ObjectMapper mapper = new ObjectMapper();
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("busStationInfo")))
                .andExpect(header("Referer", "http://localhost:8080/"))
                .andExpect(header("Origin", "http://localhost:8080"))
                .andRespond(withSuccess(("{\"result\":{\"lane\":[{\"busID\":1,\"busLocalBlID\":\"1\",\"busNo\":\"1\",\"busFirstTime\":\"%s\",\"busLastTime\":\"%s\"}]}}" ).formatted(first, last), MediaType.APPLICATION_JSON));
        TransitScheduleService result = new TransitScheduleService(transit, publicData, snapshots, mapper, rest);
        ReflectionTestUtils.setField(result, "apiKey", "test");
        ReflectionTestUtils.setField(result, "scheduleBaseUrl", "http://odsay/v1/api");
        when(snapshots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SERVICES.put(result, new Deps(transit, publicData, snapshots));
        return result;
    }

    private static final java.util.Map<TransitScheduleService, Deps> SERVICES = new java.util.IdentityHashMap<>();
    private TransitApiService serviceApi(TransitScheduleService s) { return SERVICES.get(s).transit; }
    private PublicDataTransitService publicData(TransitScheduleService s) { return SERVICES.get(s).publicData; }
    private ScheduleSnapshotRepository snapshotRepo(TransitScheduleService s) { return SERVICES.get(s).snapshots; }
    private record Deps(TransitApiService transit, PublicDataTransitService publicData, ScheduleSnapshotRepository snapshots) {}

    private ArrivalNotification notification(NotificationScheduleType type, int offset) {
        return notification(type, List.of(offset));
    }

    private ArrivalNotification notification(NotificationScheduleType type, List<Integer> offsets) {
        ArrivalNotification n = mock(ArrivalNotification.class);
        when(n.getId()).thenReturn(1L); when(n.getScheduleType()).thenReturn(type);
        when(n.getRouteDetails()).thenReturn("route");
        when(n.getReminderOffsetMinutes()).thenReturn(offsets.get(0));
        when(n.getReminderOffsetMinutesList()).thenReturn(offsets);
        return n;
    }

    private TransitDto.RouteOptionResponse route(int walk, int bus, String id) {
        return TransitDto.RouteOptionResponse.builder().routeId("r").totalDurationMinutes(walk + bus)
                .segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(walk).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS").durationMinutes(bus)
                                .odsayStartStationId("station").odsayRouteId(id).localRouteId(id)
                                .localCityCode("1000").localStationId("station").arsId("ars")
                                .transitName("1").scheduledWaitMinutes(8).build()))
                .build();
    }
}
