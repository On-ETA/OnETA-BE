package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.SeoulBusRoute;
import com.OnETA.repository.SeoulBusRouteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SeoulBusScheduleServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 18);
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final SeoulBusScheduleService service = new SeoulBusScheduleService(http, "key", "https://seoul.test",
            Clock.fixed(DAY.atTime(12, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));

    static TransitDto.RouteOptionResponse route() {
        return TransitDto.RouteOptionResponse.builder().provider("KAKAO").routeId("KAKAO_test")
                .totalDurationMinutes(30).segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(10).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS").transitName("603").durationMinutes(15)
                                .startStation("출발역").endStation("도착역")
                                .startX(126.95).startY(37.55).endX(126.96).endY(37.56)
                                .stations(List.of(TransitDto.RouteStation.builder().name("출발역").build(),
                                        TransitDto.RouteStation.builder().name("도착역").build())).build(),
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(5).build())).build();
    }

    private String xml(String items) {
        return "<ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody>" + items + "</msgBody></ServiceResult>";
    }
    private String station(String id, String ars, String name, String x, String y) {
        return "<itemList><stationId>"+id+"</stationId><arsId>"+ars+"</arsId><stationNm>"+name+
                "</stationNm><gpsX>"+x+"</gpsX><gpsY>"+y+"</gpsY></itemList>";
    }
    private void nearby() {
        server.expect(queryParam("tmX", "126.95")).andExpect(queryParam("radius", "100"))
                .andRespond(withSuccess(xml(station("100000001", "01001", "출발역", "126.95", "37.55")), MediaType.APPLICATION_XML));
        server.expect(queryParam("tmX", "126.96"))
                .andRespond(withSuccess(xml(station("100000002", "01002", "도착역", "126.96", "37.56")), MediaType.APPLICATION_XML));
    }
    private void mapping(boolean reverse, String type) {
        nearby();
        server.expect(queryParam("strSrch", "603"))
                .andRespond(withSuccess(xml("<itemList><busRouteId>100100088</busRouteId><busRouteNm>603</busRouteNm><busRouteType>"+type+"</busRouteType></itemList>"), MediaType.APPLICATION_XML));
        if (!"8".equals(type)) server.expect(queryParam("busRouteId", "100100088"))
                .andRespond(withSuccess(xml("<itemList><seq>"+(reverse?2:1)+"</seq><station>100000001</station><stationNm>출발역</stationNm><transYn>N</transYn></itemList>"
                        +"<itemList><seq>"+(reverse?1:2)+"</seq><station>100000002</station><stationNm>도착역</stationNm><transYn>N</transYn></itemList>"), MediaType.APPLICATION_XML));
    }
    private void times(String first, String last) {
        server.expect(queryParam("arsId", "01001")).andExpect(queryParam("busRouteId", "100100088"))
                .andRespond(withSuccess(xml("<itemList><arsId>01001</arsId><busRouteId>100100088</busRouteId><firstBusTm>"
                        +first+"</firstBusTm><lastBusTm>"+last+"</lastBusTm></itemList>"), MediaType.APPLICATION_XML));
    }

    @Test
    void discoversDirectNightBusWithoutRouteByStationEndpoint() {
        // getStationByPos uses stId/stNm/tmX/tmY. The previous implementation
        // incorrectly expected stationId/stationNm/gpsX/gpsY and dropped every stop.
        String origin = "<itemList><stId>121000001</stId><arsId>17107</arsId>"
                + "<stNm>거리공원</stNm><tmX>126.8920</tmX><tmY>37.5025</tmY></itemList>";

        server.expect(queryParam("tmX", "126.89170794296935"))
                .andExpect(queryParam("radius", "1000"))
                .andRespond(withSuccess(xml(origin), MediaType.APPLICATION_XML));
        server.expect(queryParam("strSrch", "N"))
                .andRespond(withSuccess(xml(
                        "<itemList><busRouteId>100100051</busRouteId><busRouteNm>N51</busRouteNm>"
                                + "<busRouteType>3</busRouteType></itemList>"),
                        MediaType.APPLICATION_XML));
        server.expect(queryParam("busRouteId", "100100051"))
                .andRespond(withSuccess(xml(
                        "<itemList><seq>1</seq><station>121000001</station><stationNm>거리공원</stationNm>"
                                + "<arsId>17107</arsId><gpsX>126.8920</gpsX><gpsY>37.5025</gpsY>"
                                + "<transYn>N</transYn></itemList>"
                                + "<itemList><seq>2</seq><station>121000050</station><stationNm>중간정류장</stationNm>"
                                + "<arsId>14500</arsId><gpsX>126.9100</gpsX><gpsY>37.5300</gpsY>"
                                + "<transYn>N</transYn></itemList>"
                                + "<itemList><seq>3</seq><station>121000099</station><stationNm>홍대입구역</stationNm>"
                                + "<arsId>14999</arsId><gpsX>126.9239</gpsX><gpsY>37.5500</gpsY>"
                                + "<transYn>N</transYn></itemList>"),
                        MediaType.APPLICATION_XML));

        var route = service.discoverDirectNightRoute(
                126.89170794296935, 37.50215130939319,
                126.92463186895164, 37.550164265498864).orElseThrow();

        assertThat(route.getProvider()).isEqualTo("SEOUL_NIGHT");
        assertThat(route.getSegments()).extracting(TransitDto.RouteSegment::getTransitType)
                .containsExactly("WALK", "BUS", "WALK");
        assertThat(route.getSegments().get(0).getDurationMinutes()).isPositive();
        assertThat(route.getTotalDurationMinutes()).isEqualTo(route.getSegments().stream()
                .mapToInt(TransitDto.RouteSegment::getDurationMinutes).sum());
        var bus = route.getSegments().get(1);
        assertThat(bus.getTransitName()).isEqualTo("N51");
        assertThat(bus.isNightBus()).isTrue();
        assertThat(bus.getStartStation()).isEqualTo("거리공원");
        assertThat(bus.getEndStation()).isEqualTo("홍대입구역");
        assertThat(bus.getStations()).extracting(TransitDto.RouteStation::getName)
                .containsExactly("거리공원", "중간정류장", "홍대입구역");
        assertThat(TransitRouteClassifier.isNightOnlyRoute(route)).isTrue();
        server.verify();
    }

    @Test
    void denseHongdaeStopsAndPartialDbDoNotHideN62FromSeoulApi() {
        SeoulBusRouteRepository db = mock(SeoulBusRouteRepository.class);
        SeoulBusRoute incomplete = mock(SeoulBusRoute.class);
        when(incomplete.getRouteId()).thenReturn("100100051");
        when(incomplete.getRouteNm()).thenReturn("N51");
        when(db.findByRouteNmContaining("N")).thenReturn(List.of(incomplete));
        var discovery = new SeoulBusScheduleService(http, "key", "https://seoul.test",
                Clock.fixed(DAY.atTime(2, 3).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        ZoneId.of("Asia/Seoul")), db);

        StringBuilder nearby = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            nearby.append("<itemList><stId>").append(120000000 + i)
                    .append("</stId><arsId>").append(10000 + i)
                    .append("</arsId><stNm>일반정류장</stNm><tmX>126.92555</tmX>")
                    .append("<tmY>37.55087</tmY></itemList>");
        }
        nearby.append("<itemList><stId>121000999</stId><arsId>17999</arsId>")
                .append("<stNm>심야정류장</stNm><tmX>126.9265</tmX><tmY>37.5505</tmY></itemList>");
        server.expect(queryParam("tmX", "126.92555"))
                .andExpect(queryParam("radius", "1000"))
                .andRespond(withSuccess(xml(nearby.toString()), MediaType.APPLICATION_XML));
        server.expect(queryParam("strSrch", "N"))
                .andRespond(withSuccess(xml("<itemList><busRouteId>100100062</busRouteId>"
                        + "<busRouteNm>N62</busRouteNm><busRouteType>3</busRouteType></itemList>"),
                        MediaType.APPLICATION_XML));
        server.expect(queryParam("busRouteId", "100100051"))
                .andRespond(withSuccess(xml(""), MediaType.APPLICATION_XML));
        server.expect(queryParam("busRouteId", "100100062"))
                .andRespond(withSuccess(xml(
                        "<itemList><seq>1</seq><station>121000999</station>"
                                + "<stationNm>심야정류장</stationNm><gpsX>126.9265</gpsX>"
                                + "<gpsY>37.5505</gpsY><arsId>17999</arsId></itemList>"
                                + "<itemList><seq>2</seq><station>121001999</station>"
                                + "<stationNm>도착정류장</stationNm><gpsX>127.0670</gpsX>"
                                + "<gpsY>37.5397</gpsY><arsId>27999</arsId></itemList>"),
                        MediaType.APPLICATION_XML));

        var discovered = discovery.discoverDirectNightRoutes(126.92555, 37.55087,
                127.0670, 37.5397);

        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0).getSegments().get(1).getTransitName()).isEqualTo("N62");
        assertThat(discovered.get(0).getProvider()).isEqualTo("SEOUL_NIGHT");
        server.verify();
    }

    @Test
    void discoveryKeepsOneBestStopPairForEachNightLine() {
        String origin = "<itemList><stId>121000001</stId><arsId>17107</arsId>"
                + "<stNm>출발정류장</stNm><tmX>126.92555</tmX>"
                + "<tmY>37.55087</tmY></itemList>";
        server.expect(queryParam("tmX", "126.92555"))
                .andRespond(withSuccess(xml(origin), MediaType.APPLICATION_XML));
        server.expect(queryParam("strSrch", "N"))
                .andRespond(withSuccess(xml(
                        "<itemList><busRouteId>100100051</busRouteId>"
                                + "<busRouteNm>N51</busRouteNm><busRouteType>3</busRouteType></itemList>"
                                + "<itemList><busRouteId>100100062</busRouteId>"
                                + "<busRouteNm>N62</busRouteNm><busRouteType>3</busRouteType></itemList>"),
                        MediaType.APPLICATION_XML));
        for (String id : List.of("100100051", "100100062")) {
            server.expect(queryParam("busRouteId", id))
                    .andRespond(withSuccess(xml(
                            "<itemList><seq>1</seq><station>121000001</station>"
                                    + "<stationNm>출발정류장</stationNm><gpsX>126.92555</gpsX>"
                                    + "<gpsY>37.55087</gpsY><arsId>17107</arsId></itemList>"
                                    + "<itemList><seq>2</seq><station>121000002</station>"
                                    + "<stationNm>도착정류장</stationNm><gpsX>126.93555</gpsX>"
                                    + "<gpsY>37.56087</gpsY><arsId>17108</arsId></itemList>"),
                            MediaType.APPLICATION_XML));
        }
        var results = service.discoverDirectNightRoutes(126.92555, 37.55087,
                126.93555, 37.56087);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(r -> r.getSegments().get(1).getTransitName())
                .containsExactlyInAnyOrder("N51", "N62");
        server.verify();
    }


    @Test
    void mergedNightRouteListIsCachedForFifteenMinutesThenRefreshed() {
        MutableClock clock = new MutableClock();
        SeoulBusRouteRepository db = mock(SeoulBusRouteRepository.class);
        SeoulBusRoute stored = mock(SeoulBusRoute.class);
        when(stored.getRouteId()).thenReturn("100100051");
        when(stored.getRouteNm()).thenReturn("N51");
        when(db.findByRouteNmContaining("N")).thenReturn(List.of(stored));
        var client = new SeoulBusScheduleService(http, "key", "https://seoul.test", clock, db);

        server.expect(queryParam("strSrch", "N"))
                .andRespond(withSuccess(xml("<itemList><busRouteId>100100062</busRouteId>"
                        + "<busRouteNm>N62</busRouteNm><busRouteType>3</busRouteType></itemList>"),
                        MediaType.APPLICATION_XML));

        List<?> first = ReflectionTestUtils.invokeMethod(client, "routeCandidates", "N");
        List<?> cached = ReflectionTestUtils.invokeMethod(client, "routeCandidates", "N");
        assertThat(first).hasSize(2);
        assertThat(cached).isSameAs(first);
        verify(db, times(1)).findByRouteNmContaining("N");

        clock.advance(Duration.ofMinutes(15).plusMillis(1));
        server.expect(queryParam("strSrch", "N"))
                .andRespond(withSuccess(xml("<itemList><busRouteId>100100061</busRouteId>"
                        + "<busRouteNm>N61</busRouteNm><busRouteType>3</busRouteType></itemList>"),
                        MediaType.APPLICATION_XML));

        List<?> refreshed = ReflectionTestUtils.invokeMethod(client, "routeCandidates", "N");
        assertThat(refreshed).hasSize(2);
        assertThat(refreshed.toString()).contains("N51", "N61").doesNotContain("N62");
        verify(db, times(2)).findByRouteNmContaining("N");
        server.verify();
    }

    @Test
    void failedNightRouteListUsesOwnBackoffAndKeepsDatabaseAndArrivalsAvailable() {
        MutableClock clock = new MutableClock();
        SeoulBusRouteRepository db = mock(SeoulBusRouteRepository.class);
        SeoulBusRoute stored = mock(SeoulBusRoute.class);
        when(stored.getRouteId()).thenReturn("100100051");
        when(stored.getRouteNm()).thenReturn("N51");
        when(db.findByRouteNmContaining("N")).thenReturn(List.of(stored));
        var client = new SeoulBusScheduleService(http, "key", "https://seoul.test", clock, db);

        server.expect(queryParam("strSrch", "N"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        List<?> fallback = ReflectionTestUtils.invokeMethod(client, "routeCandidates", "N");
        assertThat(fallback).hasSize(1);
        assertThat(fallback.toString()).contains("N51");
        assertThat(ReflectionTestUtils.getField(client, "retryAfter")).isEqualTo(Instant.EPOCH);
        assertThat((Instant) ReflectionTestUtils.getField(client, "routeListRetryAfter"))
                .isAfter(clock.instant());

        // Do not call the failing route-list endpoint again during its backoff.
        List<?> repeatedFallback = ReflectionTestUtils.invokeMethod(client, "routeCandidates", "N");
        assertThat(repeatedFallback).hasSize(1);

        // A failed discovery must not put the separate arrival endpoint into backoff.
        server.expect(queryParam("busRouteId", "100100051"))
                .andRespond(withSuccess(xml(""), MediaType.APPLICATION_XML));
        var schedule = new SeoulBusScheduleService.Schedule("100000001", "01001", "100100051",
                DAY.atTime(0, 0), DAY.atTime(23, 59), 1, "100000002", 2);
        assertThat(client.arrivals(schedule, DAY.atTime(12, 0))).isEmpty();

        clock.advance(Duration.ofSeconds(61));
        server.expect(queryParam("strSrch", "N"))
                .andRespond(withSuccess(xml("<itemList><busRouteId>100100062</busRouteId>"
                        + "<busRouteNm>N62</busRouteNm><busRouteType>3</busRouteType></itemList>"),
                        MediaType.APPLICATION_XML));
        List<?> recovered = ReflectionTestUtils.invokeMethod(client, "routeCandidates", "N");
        assertThat(recovered).hasSize(2);
        assertThat(recovered.toString()).contains("N51", "N62");
        server.verify();
    }

    private static final class MutableClock extends Clock {
        private Instant now = DAY.atTime(12, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();

        @Override public ZoneId getZone() { return ZoneId.of("Asia/Seoul"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }

        void advance(Duration duration) { now = now.plus(duration); }
    }

    @Test
    void resolvesExactDirectionAndCachesCurrentDaySchedule() {
        mapping(false, "3"); times("20260918053000", "20260919003000");
        var schedule = service.resolve(route(), DAY);
        assertThat(schedule.first()).isEqualTo(DAY.atTime(5, 30));
        assertThat(schedule.last()).isEqualTo(DAY.plusDays(1).atTime(0, 30));
        assertThat(schedule.arsId()).isEqualTo("01001");
        assertThat(service.resolve(route(), DAY)).isEqualTo(schedule);
        server.verify();
    }
    @Test
    void normalizesTimeOnlyMidnightLastBus() {
        mapping(false, "3"); times("05:30", "00:30");
        assertThat(service.resolve(route(), DAY).last()).isEqualTo(DAY.plusDays(1).atTime(0, 30));
        server.verify();
    }
    @Test
    void acceptsStillActivePreviousOperatingDayAt0203() {
        var midnight = new SeoulBusScheduleService(http, "key", "https://seoul.test",
                Clock.fixed(DAY.atTime(2, 3).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        ZoneId.of("Asia/Seoul")));
        mapping(false, "3");
        times("20260917230000", "20260918033000");

        var schedule = midnight.resolve(route(), DAY);

        assertThat(schedule.first()).isEqualTo(DAY.minusDays(1).atTime(23, 0));
        assertThat(schedule.last()).isEqualTo(DAY.atTime(3, 30));
        server.verify();
    }

    @Test
    void previousOperatingDayAlreadyEndedAt0203RemainsUnsupported() {
        var midnight = new SeoulBusScheduleService(http, "key", "https://seoul.test",
                Clock.fixed(DAY.atTime(2, 3).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        ZoneId.of("Asia/Seoul")));
        mapping(false, "3");
        times("20260917230000", "20260918013000");

        assertUnsupported(() -> midnight.resolve(route(), DAY));
        server.verify();
    }

    @Test
    void rejectsReverseDirectionInsteadOfUsingNearbyOppositeStop() {
        mapping(true, "3");
        assertUnsupported(() -> service.resolve(route(), DAY)); server.verify();
    }
    @Test
    void rejectsGyeonggiRouteEvenWhenItPassesThroughSeoul() {
        mapping(false, "8");
        assertUnsupported(() -> service.resolve(route(), DAY)); server.verify();
    }
    @Test
    void rejectsMissingOrStaleTimes() {
        mapping(false, "3"); times("20260917053000", "20260917233000");
        assertUnsupported(() -> service.resolve(route(), DAY)); server.verify();
    }
    @Test
    void rejectsTransferAndSubwayBeforeNetworkCall() {
        var route = route();
        var bus = route.getSegments().get(1);
        assertUnsupported(() -> service.resolve(route.toBuilder().segments(List.of(bus, bus)).build(), DAY));
        assertUnsupported(() -> service.resolve(route.toBuilder().segments(List.of(bus.toBuilder().transitType("SUBWAY").build())).build(), DAY));
        server.verify();
    }
    @Test
    void authFailureIsSanitizedAndBackedOff() {
        server.expect(queryParam("tmX", "126.95")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        for (int i = 0; i < 2; i++) assertThatThrownBy(() -> service.resolve(route(), DAY))
                .isInstanceOfSatisfying(GlobalException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE))
                .hasMessageNotContaining("key");
        server.verify();
    }
    @Test
    void doesNotReuseTodayForAnotherServiceDay() {
        assertUnsupported(() -> service.resolve(route(), DAY.minusDays(1)));
        server.verify();
    }
    @ParameterizedTest
    @ValueSource(strings={"", "null", "29:99", "202609170000", "20260230053000"})
    void rejectsInvalidTimes(String time) { assertUnsupported(() -> SeoulBusScheduleService.parseTime(time, DAY)); }

    private void assertUnsupported(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
    }

    @Test
    void matchesPlatformAnnotationsWithoutDroppingDirectionIdentity() {
        mapping(false, "3"); times("0530", "2330");
        var original = route();
        var bus = original.getSegments().get(1).toBuilder().startStation("출발역(7번승강장)(중)")
                .stations(List.of(TransitDto.RouteStation.builder().name("출발역(7번승강장)(중)").build(),
                        TransitDto.RouteStation.builder().name("도착역").build())).build();
        assertThat(service.resolve(original.toBuilder().segments(List.of(bus)).build(), DAY).order()).isEqualTo(1);
        server.verify();
    }

    @Test
    void resolvesEveryBusOfTransferRouteAndReusesMappingCache() {
        mapping(false, "3"); times("0530", "2330");
        var bus = route().getSegments().get(1);
        var transfer = route().toBuilder().transferCount(1).segments(List.of(bus, bus)).build();
        assertThat(service.resolveRoute(transfer, DAY)).hasSize(2);
        server.verify();
    }

    @Test
    void resolvesBusAndSubwayTogetherInOriginalSegmentOrder() {
        mapping(false, "3"); times("0530", "2330");
        var subwayClient = org.mockito.Mockito.mock(TagoSubwayScheduleService.class);
        service.setSubway(subwayClient);
        var bus = route().getSegments().get(1);
        var subway = bus.toBuilder().transitType("SUBWAY").transitName("4호선").build();
        var schedule = new SeoulBusScheduleService.Schedule("START", "", "TAGO_SUBWAY:4호선",
                DAY.atTime(6, 0), DAY.atTime(23, 50));
        org.mockito.Mockito.when(subwayClient.resolve(subway, DAY)).thenReturn(schedule);
        var resolved = service.resolveRoute(route().toBuilder().segments(List.of(bus, subway)).build(), DAY);
        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0).routeId()).isEqualTo("100100088");
        assertThat(resolved.get(1)).isEqualTo(schedule);
        server.verify();
    }

    @Test
    void livePredictionsUseExactStopOrderSameVehicleAndProviderObservationTime() {
        var schedule = new SeoulBusScheduleService.Schedule("100000001", "01001", "100100088",
                DAY.atTime(5, 30), DAY.atTime(23, 30), 10, "100000002", 20);
        String start = "<itemList><stId>100000001</stId><staOrd>10</staOrd><mkTm>2026-09-18 12:00:00.0</mkTm>"
                + "<vehId1>111</vehId1><exps1>600</exps1><isLast1>1</isLast1>"
                + "<vehId2>222</vehId2><exps2>900</exps2><full2>1</full2></itemList>";
        String end = "<itemList><stId>100000002</stId><staOrd>20</staOrd><mkTm>2026-09-18 12:00:00.0</mkTm>"
                + "<vehId1>111</vehId1><exps1>1200</exps1></itemList>";
        server.expect(requestTo("https://seoul.test/api/rest/arrive/getArrInfoByRouteAll?ServiceKey=key&busRouteId=100100088"))
                .andRespond(withSuccess(xml(start + end), MediaType.APPLICATION_XML));
        var arrivals = service.arrivals(schedule, DAY.atTime(12, 0));
        assertThat(arrivals).hasSize(1);
        assertThat(arrivals.get(0).boarding()).isEqualTo(DAY.atTime(12, 10));
        assertThat(arrivals.get(0).alighting()).isEqualTo(DAY.atTime(12, 20));
        assertThat(arrivals.get(0).last()).isTrue();
        // Cached predictions must not slide forward when polled again.
        assertThat(service.arrivals(schedule, DAY.atTime(12, 0, 10))).isEqualTo(arrivals);
        assertThat(service.arrivals(schedule, DAY.atTime(12, 2))).isEmpty();
        var wrongOrder = new SeoulBusScheduleService.Schedule("100000001", "01001", "100100088",
                DAY.atTime(5, 30), DAY.atTime(23, 30), 11, "100000002", 20);
        assertThat(service.arrivals(wrongOrder, DAY.atTime(12, 0))).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a+b/c==", "a%2Bb%2Fc%3D%3D"})
    void preservesReservedCharactersInRawAndEncodedServiceKeys(String key) {
        var client = new SeoulBusScheduleService(http, key, "https://seoul.test",
                Clock.fixed(DAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));
        server.expect(request -> assertThat(request.getURI().getRawQuery()).contains("ServiceKey=a%2Bb%2Fc%3D%3D"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.resolve(route(), DAY)).isInstanceOf(GlobalException.class);
        server.verify();
    }
}
