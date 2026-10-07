package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.NotificationScheduleType;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TransitDepartureTimeTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 7);

    @Test
    void lastUsesDepartureInsteadOfArrivalAndSubtractsAccessWalk() {
        var fixture = fixture("23:58:00", "24:10:00", "4");
        assertThat(fixture.service().previewDepartureForServiceDate(route(17),
                NotificationScheduleType.LAST_TRANSIT, DAY, new HashMap<>()))
                .isEqualTo(DAY.atTime(23, 41));
        fixture.server().verify();
    }

    @Test
    void lastSupportsBothZeroAndTwentyFourHourMidnightEncoding() {
        for (String departure : List.of("00:20:00", "24:20:00")) {
            var fixture = fixture(departure, "24:32:00", "4");
            assertThat(fixture.service().previewDepartureForServiceDate(route(10),
                    NotificationScheduleType.LAST_TRANSIT, DAY, new HashMap<>()))
                    .isEqualTo(DAY.plusDays(1).atTime(0, 10));
            fixture.server().verify();
        }
    }

    @Test
    void firstContinuesToUseBoardingDeparture() {
        var fixture = fixture("05:30:00", "05:42:00", "3");
        assertThat(fixture.service().previewDepartureForServiceDate(route(10),
                NotificationScheduleType.FIRST_TRANSIT, DAY, new HashMap<>()))
                .isEqualTo(DAY.atTime(5, 20));
        fixture.server().verify();
    }

    @Test
    void arrivalOnlyResponseIsUnsupportedInsteadOfInventingDeparture() {
        var fixture = fixture("", "23:58:00", "4");
        assertThatThrownBy(() -> fixture.service().previewDepartureForServiceDate(route(0),
                NotificationScheduleType.LAST_TRANSIT, DAY, new HashMap<>()))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED));
        fixture.server().verify();
    }

    private Fixture fixture(String departure, String arrival, String mode) {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(org.hamcrest.Matchers.containsString("subwayPathSchedule")))
                .andExpect(queryParam("SID", "239"))
                .andExpect(queryParam("EID", "234"))
                .andExpect(queryParam("MODE", mode))
                .andRespond(withSuccess("""
                        {"result":{"path":[{"info":{"departureTime":"%s","arrivalTime":"%s"}}]}}
                        """.formatted(departure, arrival), MediaType.APPLICATION_JSON));
        var service = new TransitScheduleService(null, null, null, new ObjectMapper(), rest);
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(service, "scheduleBaseUrl", "http://odsay/v1/api");
        return new Fixture(service, server);
    }

    private TransitDto.RouteOptionResponse route(int walk) {
        return TransitDto.RouteOptionResponse.builder().provider("ODSAY").routeId("R")
                .totalDurationMinutes(walk + 12)
                .segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(walk).build(),
                        TransitDto.RouteSegment.builder().transitType("SUBWAY").transitName("2호선")
                                .durationMinutes(12).odsayStartStationId("239").odsayEndStationId("234").build()))
                .build();
    }

    private record Fixture(TransitScheduleService service, MockRestServiceServer server) { }
}
