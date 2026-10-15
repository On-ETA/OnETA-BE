package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TagoSubwayScheduleServiceTest {
    private final LocalDate day = LocalDate.of(2026, 9, 18);

    @ParameterizedTest
    @CsvSource({
            "신분당선,신분당선,강남,양재,127.02,37.49",
            "수도권 수인·분당선,수인분당선,수원,수원시청,127.00,37.26",
            "인천1호선,인천 1호선,인천터미널,부평,126.70,37.44",
            "인천지하철1호선,인천1호선,인천터미널,부평,126.70,37.44",
            "신분당선,신분당선,강남역,양재역,127.02,37.49"
    })
    void resolvesNamedLinesOutsideSeoulUsingExactStationLineIds(
            String requestedLine, String providerLine, String start, String end, double x, double y) {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(ExpectedCount.manyTimes(), request -> { }).andRespond(request -> {
            String query = java.net.URLDecoder.decode(request.getURI().getRawQuery(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String items;
            if (request.getURI().getPath().contains("Kwrd")) {
                boolean startQuery = java.util.Arrays.stream(query.split("&"))
                        .anyMatch(part -> part.equals("subwayStationName=" + start));
                String name = startQuery ? start : end;
                String id = name.equals(start) ? "START" : "END";
                name = name.replaceFirst("역$", "");
                // The same station name on another line must never be selected.
                items = "[{\"subwayStationName\":\"" + name + "\",\"subwayRouteName\":\"2호선\","
                        + "\"subwayStationId\":\"WRONG_LINE\"},{\"subwayStationName\":\"" + name
                        + "\",\"subwayRouteName\":\"" + providerLine + "\",\"subwayStationId\":\"" + id + "\"}]";
            } else {
                assertThat(query).doesNotContain("WRONG_LINE");
                if (query.contains("upDownTypeCode=U")) items = "[]";
                else {
                    boolean atStart = query.contains("subwayStationId=START");
                    items = "[" + row(atStart ? "060000" : "060500") + ","
                            + row(atStart ? "233000" : "233500") + "]";
                }
            }
            return withSuccess(response(items), MediaType.APPLICATION_JSON).createResponse(request);
        });
        var segment = TransitDto.RouteSegment.builder().transitType("SUBWAY").transitName(requestedLine)
                .startStation(start).endStation(end).durationMinutes(5)
                .startX(x).startY(y).endX(x + 0.01).endY(y + 0.01)
                .stations(List.of(station(start), station(end))).build();
        var service = new TagoSubwayScheduleService(new ObjectMapper(), "key", "https://test", http);

        var schedule = service.resolve(segment, day);

        assertThat(schedule.first()).isEqualTo(day.atTime(6, 0));
        assertThat(schedule.last()).isEqualTo(day.atTime(23, 30));
        server.verify();
    }

    @Test void rejectsDifferentLineInsteadOfAcceptingAnUnverifiedNamedLine() {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(ExpectedCount.once(), request -> { }).andRespond(withSuccess(response(
                "[{\"subwayStationName\":\"강남\",\"subwayRouteName\":\"2호선\",\"subwayStationId\":\"WRONG\"}]"),
                MediaType.APPLICATION_JSON));
        var segment = TransitDto.RouteSegment.builder().transitType("SUBWAY").transitName("신분당선")
                .startStation("강남").endStation("양재").durationMinutes(5)
                .stations(List.of(station("강남"), station("양재"))).build();
        var service = new TagoSubwayScheduleService(new ObjectMapper(), "key", "https://test", http);

        assertThatThrownBy(() -> service.resolve(segment, day))
                .isInstanceOfSatisfying(com.OnETA.common.exception.GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(
                                com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
        server.verify();
    }

    private String response(String items) {
        int count = new ObjectMapper().readTree(items).size();
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":"
                + count + ",\"items\":{\"item\":" + items + "}}}}";
    }
    @Test void resolvesLineAndDirectionAndRollsMidnightIntoServiceDay() {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        server.expect(ExpectedCount.manyTimes(), request -> {
            assertThat(request.getURI().getRawQuery()).contains("serviceKey=a%2Bb%3D");
        }).andRespond(request -> {
            String query = java.net.URLDecoder.decode(request.getURI().getRawQuery(), java.nio.charset.StandardCharsets.UTF_8);
            String items;
            if (request.getURI().getPath().contains("Kwrd")) {
                String name = query.contains("서울역") ? "서울역" : query.contains("회현") ? "회현" : "명동";
                String id = name.equals("서울역") ? "START" : name.equals("회현") ? "NEXT" : "END";
                items = "[{\"subwayStationName\":\""+name+"\",\"subwayRouteName\":\"4호선\",\"subwayStationId\":\""+id+"\"}]";
            } else if (query.contains("upDownTypeCode=U")) items = "[]";
            else {
                String time = query.contains("subwayStationId=START") ? "060000" : query.contains("subwayStationId=NEXT") ? "060200" : "060500";
                String late = query.contains("subwayStationId=START") ? "001000" : query.contains("subwayStationId=NEXT") ? "001200" : "001500";
                items = "["+row(time)+","+row(late)+"]";
            }
            int count = new ObjectMapper().readTree(items).size();
            return withSuccess("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":"+count+",\"items\":{\"item\":"+items+"}}}}",
                    MediaType.APPLICATION_JSON).createResponse(request);
        });
        var service = new TagoSubwayScheduleService(new ObjectMapper(), "a+b=", "https://test", http);
        var segment = TransitDto.RouteSegment.builder().transitType("SUBWAY").transitName("4호선")
                .startStation("서울역").endStation("명동").durationMinutes(5)
                .startX(126.97).startY(37.55).endX(126.98).endY(37.56)
                .stations(List.of(station("서울역"), station("회현"), station("명동"))).build();
        var schedule = service.resolve(segment, day);
        assertThat(schedule.first()).isEqualTo(day.atTime(6, 0));
        assertThat(schedule.last()).isEqualTo(day.plusDays(1).atTime(0, 10));
        assertThat(service.resolve(segment, day)).isEqualTo(schedule);
        server.verify();
    }
    @Test
    void hangulDayUsesOnlyHolidayDailyTypeForTagoSubwayTimetables() {
        var http = new RestTemplate();
        var server = MockRestServiceServer.bindTo(http).build();
        var timetableRequests = new java.util.concurrent.atomic.AtomicInteger();
        server.expect(ExpectedCount.manyTimes(), request -> {
            if (request.getURI().getPath().contains("GetSubwaySttnAcctoSchdulList")) {
                String query = request.getURI().getRawQuery();
                assertThat(query).contains("dailyTypeCode=03").doesNotContain("dailyTypeCode=01");
                timetableRequests.incrementAndGet();
            }
        }).andRespond(request -> {
            String decoded = java.net.URLDecoder.decode(request.getURI().getRawQuery(),
                    java.nio.charset.StandardCharsets.UTF_8);
            String items = "[]";
            if (request.getURI().getPath().contains("Kwrd")) {
                String name = decoded.contains("서울역") ? "서울역"
                        : decoded.contains("회현") ? "회현" : "명동";
                items = "[{\"subwayStationName\":\"" + name
                        + "\",\"subwayRouteName\":\"4호선\",\"subwayStationId\":\"" + name + "\"}]";
            }
            int count = new ObjectMapper().readTree(items).size();
            return withSuccess("{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                            + "\"body\":{\"totalCount\":" + count + ",\"items\":{\"item\":" + items + "}}}}",
                    MediaType.APPLICATION_JSON).createResponse(request);
        });
        var service = new TagoSubwayScheduleService(
                new ObjectMapper(), "key", "https://test", http);
        var segment = TransitDto.RouteSegment.builder().transitType("SUBWAY").transitName("4호선")
                .startStation("서울역").endStation("명동").durationMinutes(5)
                .startX(126.97).startY(37.55).endX(126.98).endY(37.56)
                .stations(List.of(station("서울역"), station("회현"), station("명동"))).build();

        assertThatThrownBy(() -> service.resolve(segment, LocalDate.of(2026, 10, 9)))
                .isInstanceOf(com.OnETA.common.exception.GlobalException.class);
        assertThat(timetableRequests.get()).isGreaterThan(0);
        server.verify();
    }

    @Test void rejectsInvalidTimeAndPreservesMidnightRollover() {
        assertThat(TagoSubwayScheduleService.time("0", day)).isNull();
        assertThat(TagoSubwayScheduleService.time("296199", day)).isNull();
        assertThat(TagoSubwayScheduleService.time("001400", day)).isEqualTo(day.plusDays(1).atTime(0, 14));
    }
    private String row(String time) { return "{\"depTime\":\""+time+"\",\"arrTime\":\""+time+"\",\"endSubwayStationId\":\"END\"}"; }
    private TransitDto.RouteStation station(String name) { return TransitDto.RouteStation.builder().name(name).build(); }
}
