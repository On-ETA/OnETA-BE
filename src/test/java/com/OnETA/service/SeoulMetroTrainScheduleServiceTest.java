package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SeoulMetroTrainScheduleServiceTest {

    @Test
    void resolvesSaturdayFirstAndLastByMatchingSameTrainAcrossBothStations() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        var service = new SeoulMetroTrainScheduleService(
                new ObjectMapper(), http, "a+b=", "https://metro.test/getTrainSch");

        server.expect(requestTo(org.hamcrest.Matchers.containsString("getTrainSch")))
                .andRespond(withSuccess(response(items(
                        row("100", "05:32:00", "05:31:00", "내선"),
                        row("200", "23:55:00", "23:54:00", "내선"))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getTrainSch")))
                .andRespond(withSuccess(response(items(
                        row("100", "05:43:00", "05:42:00", "내선"),
                        row("200", "00:06:00", "00:05:00", "내선"))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getTrainSch")))
                .andRespond(withSuccess(response(""), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getTrainSch")))
                .andRespond(withSuccess(response(""), MediaType.APPLICATION_JSON));

        LocalDate saturday = LocalDate.of(2026, 10, 3);
        var schedule = service.resolve(segment(), saturday);

        assertThat(schedule.first()).isEqualTo(saturday.atTime(5, 32));
        assertThat(schedule.last()).isEqualTo(saturday.atTime(23, 55));
        assertThat(schedule.routeId()).isEqualTo("SEOUL_METRO:2호선");
        server.verify();
    }

    @Test
    void ignoresSameTrainMatchWhenDirectionWouldTakeFarLongerThanKakaoSegment() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        var service = new SeoulMetroTrainScheduleService(
                new ObjectMapper(), http, "key", "https://metro.test/getTrainSch");

        server.expect(requestTo(org.hamcrest.Matchers.containsString("getTrainSch")))
                .andRespond(withSuccess(response(items(
                        row("100", "05:32:00", "05:31:00", "내선"))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getTrainSch")))
                .andRespond(withSuccess(response(items(
                        row("100", "05:43:00", "05:42:00", "내선"))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getTrainSch")))
                .andRespond(withSuccess(response(items(
                        row("900", "05:00:00", "04:59:00", "외선"))), MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("getTrainSch")))
                .andRespond(withSuccess(response(items(
                        row("900", "06:10:00", "06:09:00", "외선"))), MediaType.APPLICATION_JSON));

        var schedule = service.resolve(segment(), LocalDate.of(2026, 10, 3));
        assertThat(schedule.first()).isEqualTo(LocalDate.of(2026, 10, 3).atTime(5, 32));
        server.verify();
    }

    @Test
    void midnightTimeBelongsToEndOfServiceDay() {
        LocalDate day = LocalDate.of(2026, 10, 3);
        assertThat(SeoulMetroTrainScheduleService.scheduleTime("00:18:00", day))
                .isEqualTo(day.plusDays(1).atTime(0, 18));
        assertThat(SeoulMetroTrainScheduleService.scheduleTime("05:32:00", day))
                .isEqualTo(day.atTime(5, 32));
    }

    private TransitDto.RouteSegment segment() {
        return TransitDto.RouteSegment.builder()
                .transitType("SUBWAY")
                .transitName("2호선")
                .durationMinutes(10)
                .startStation("신도림")
                .endStation("합정")
                .stations(List.of(
                        TransitDto.RouteStation.builder().name("신도림").build(),
                        TransitDto.RouteStation.builder().name("문래").build(),
                        TransitDto.RouteStation.builder().name("영등포구청").build(),
                        TransitDto.RouteStation.builder().name("당산").build(),
                        TransitDto.RouteStation.builder().name("합정").build()))
                .build();
    }

    private String response(String items) {
        String payload = items == null || items.isBlank() ? "\"\"" : items;
        return "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                + "\"body\":{\"items\":" + payload + ",\"numOfRows\":1000,\"pageNo\":1,\"totalCount\":2}}}";
    }

    private String items(String... rows) {
        return "{\"item\":[" + String.join(",", rows) + "]}";
    }

    private String row(String trainNo, String departure, String arrival, String direction) {
        return "{\"trainno\":\"" + trainNo + "\",\"trainDptreTm\":\"" + departure
                + "\",\"trainArvlTm\":\"" + arrival + "\",\"upbdnbSe\":\"" + direction + "\"}";
    }
}
