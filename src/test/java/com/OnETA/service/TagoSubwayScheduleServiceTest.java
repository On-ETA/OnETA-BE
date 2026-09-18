package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import org.junit.jupiter.api.Test;
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
    @Test void rejectsInvalidTimeAndPreservesMidnightRollover() {
        assertThat(TagoSubwayScheduleService.time("0", day)).isNull();
        assertThat(TagoSubwayScheduleService.time("296199", day)).isNull();
        assertThat(TagoSubwayScheduleService.time("001400", day)).isEqualTo(day.plusDays(1).atTime(0, 14));
    }
    private String row(String time) { return "{\"depTime\":\""+time+"\",\"arrTime\":\""+time+"\",\"endSubwayStationId\":\"END\"}"; }
    private TransitDto.RouteStation station(String name) { return TransitDto.RouteStation.builder().name(name).build(); }
}
