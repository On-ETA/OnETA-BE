package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OdsayConnectedLastSubwayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocalDate day = LocalDate.of(2026, 10, 9);

    @Test
    void usesTheActualReachableSixToTwoTrainPairNotIndependentLastBoundaries() {
        var route = route();
        var response = timetable(
                leg("600", "601", "6호선", "23:40:00", "23:42:00"),
                transfer(),
                leg("200", "201", "2호선", "23:48:00", "23:57:00"));
        // The full timetable confirms a 6-minute transfer gap at Hapjeong.
        // Leaving home 9 + 5 minutes before the 23:40 train means 23:26.
        assertThat(OdsayConnectedLastSubway.find(route, response, day))
                .contains(day.atTime(23, 26));
        assertThat(OdsayConnectedLastSubway.originId(route)).isEqualTo("600");
        assertThat(OdsayConnectedLastSubway.destinationId(route)).isEqualTo("201");
    }

    @Test
    void doesNotSubstituteAnotherLineOrDifferentStationForChosenRoute() {
        assertThat(OdsayConnectedLastSubway.find(route(), timetable(
                leg("600", "601", "6호선", "23:40:00", "23:42:00"),
                transfer(),
                leg("200", "201", "5호선", "23:48:00", "23:57:00")), day))
                .isEmpty();
        assertThat(OdsayConnectedLastSubway.find(route(), timetable(
                leg("600", "601", "6호선", "23:40:00", "23:42:00"),
                transfer(),
                leg("202", "201", "2호선", "23:48:00", "23:57:00")), day))
                .isEmpty();
    }

    @Test
    void rejectsLastTrainsWhoseTransferGapIsShorterThanRequiredWalk() {
        assertThat(OdsayConnectedLastSubway.find(route(), timetable(
                leg("600", "601", "6호선", "23:40:00", "23:42:00"),
                transfer(),
                leg("200", "201", "2호선", "23:43:00", "23:55:00")), day))
                .isEmpty();
    }

    @Test
    void handlesLastTrainConnectionsAfterMidnightAsThePreviousServiceDay() {
        assertThat(OdsayConnectedLastSubway.find(route(), timetable(
                leg("600", "601", "6호선", "23:57:00", "00:00:00"),
                transfer(),
                leg("200", "201", "2호선", "00:05:00", "00:17:00")), day))
                .contains(day.atTime(23, 43));
    }

    @Test
    void neverUsesAPathWithMissingTrainArrivalOrDeparture() {
        assertThat(OdsayConnectedLastSubway.find(route(), timetable(
                leg("600", "601", "6호선", "23:40:00", ""),
                transfer(),
                leg("200", "201", "2호선", "23:48:00", "23:57:00")), day))
                .isEmpty();
    }

    private TransitDto.RouteOptionResponse route() {
        return TransitDto.RouteOptionResponse.builder()
                .provider("ODSAY").routeId("SANGSU-HAPJEONG-SINDORIM")
                .totalDurationMinutes(37)
                .segments(List.of(
                        segment("WALK", "", "", "", "", 9),
                        segment("SUBWAY", "6호선", "600", "601", "상수", 2),
                        segment("WALK", "", "", "", "", 2),
                        segment("SUBWAY", "2호선", "200", "201", "합정", 9),
                        segment("WALK", "", "", "", "", 13)))
                .build();
    }

    private TransitDto.RouteSegment segment(String type, String line, String sid, String eid,
                                             String startName, int duration) {
        return TransitDto.RouteSegment.builder()
                .transitType(type).transitName(line)
                .odsayStartStationId(sid).odsayEndStationId(eid)
                .startStation(startName).durationMinutes(duration).build();
    }

    private tools.jackson.databind.JsonNode timetable(String... legs) {
        return mapper.readTree("{\"result\":{\"path\":[{\"info\":{\"departureTime\":\"23:40:00\"},"
                + "\"subPath\":[" + String.join(",", legs) + "]}]}}");
    }

    private String leg(String from, String to, String name, String dep, String arr) {
        return "{\"movingType\":1,\"startID\":\"" + from + "\",\"endID\":\"" + to
                + "\",\"laneName\":\"" + name + "\",\"departureTime\":\"" + dep
                + "\",\"arrivalTime\":\"" + arr + "\"}";
    }

    private String transfer() {
        return "{\"movingType\":2,\"sectionTime\":2}";
    }
}
