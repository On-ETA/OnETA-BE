package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
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
        server.expect(requestTo("https://seoul.test/api/rest/stationinfo/getRouteByStation?serviceKey=key&arsId=01001"))
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
        assertThatThrownBy(() -> service.resolve(route(), DAY.minusDays(1))).isInstanceOf(GlobalException.class);
        server.verify();
    }
    @ParameterizedTest
    @ValueSource(strings={"", "null", "29:99", "202609170000", "20260230053000"})
    void rejectsInvalidTimes(String time) { assertUnsupported(() -> SeoulBusScheduleService.parseTime(time, DAY)); }

    private void assertUnsupported(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a+b/c==", "a%2Bb%2Fc%3D%3D"})
    void preservesReservedCharactersInRawAndEncodedServiceKeys(String key) {
        var client = new SeoulBusScheduleService(http, key, "https://seoul.test",
                Clock.fixed(DAY.atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul")));
        server.expect(request -> assertThat(request.getURI().getRawQuery()).contains("serviceKey=a%2Bb%2Fc%3D%3D"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> client.resolve(route(), DAY)).isInstanceOf(GlobalException.class);
        server.verify();
    }
}
