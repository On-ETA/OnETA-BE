package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.NotificationScheduleType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoEndpointWalkingTest {
    private static final double SX = 126.97209238331357, SY = 37.55597933890212;
    private static final double BOARD_X = 126.97438543, BOARD_Y = 37.55890357;
    private static final double ALIGHT_X = 127.11119093, ALIGHT_Y = 37.39148774;
    private static final double EX = 127.11119782955879, EY = 37.39477752734142;
    // Minimal fixture from the Seoul Station -> Pangyo live response: no endpoint WALKING steps.
    private static final String BUS = """
            {"properties":{"type":"BUS","time":2291,
              "stops":[{"name":"숭례문"},{"name":"판교역.낙생육교.현대백화점"}],
              "vehicles":[{"name":"9401"}]},
             "path":{"points":[[126.97438543,37.55890357],[127.11119093,37.39148774]]}}
            """;
    private static final String WALK = "{\"properties\":{\"type\":\"WALKING\",\"time\":120}}";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final KakaoTransitClient client = new KakaoTransitClient(mapper, http, "test-key", true);

    @ParameterizedTest
    @EnumSource(value = NotificationScheduleType.class, names = {"FIRST_TRANSIT", "LAST_TRANSIT"})
    void addsActualEndpointWalksAndAdvancesDepartureWithoutAddingToProviderTotal(NotificationScheduleType type) {
        expectTransit(route(BUS));
        expectWalk(SX, SY, BOARD_X, BOARD_Y, 378);
        expectWalk(ALIGHT_X, ALIGHT_Y, EX, EY, 452);
        var result = search().get(0);
        assertThat(result.getSegments()).extracting(TransitDto.RouteSegment::getTransitType)
                .containsExactly("WALK", "BUS", "WALK");
        assertThat(result.getSegments()).extracting(TransitDto.RouteSegment::getDurationMinutes)
                .containsExactly(7, 39, 8);
        assertThat(result.getTotalDurationMinutes()).isEqualTo(53);
        assertThat(result.getRealTimeDurationMinutes()).isEqualTo(53);
        assertThat(result.getSegments().get(0).getStartX()).isEqualTo(SX);
        assertThat(result.getSegments().get(2).getEndY()).isEqualTo(EY);
        // Exercise serialization used by routeDetails and the same calculator used by first/last alerts.
        var transit = new TransitApiService(mock(PublicDataTransitService.class), mapper, http);
        var saved = transit.readSavedRoute(mapper.writeValueAsString(result));
        var boarding = LocalDateTime.of(2026, 9, 28, 5, 30);
        var plan = TransitScheduleCalculator.calculate(saved.getSegments(), List.of(boarding), type);
        assertThat(plan.departure()).isEqualTo(boarding.minusMinutes(7));
        assertThat(plan.durationMinutes()).isEqualTo(54); // each segment is rounded up separately
        server.verify();
    }

    @Test
    void keepsExistingWalksWithoutAdditionalRequestsOrDoubleCounting() {
        expectTransit(route(WALK + "," + BUS + "," + WALK));
        var result = search().get(0);
        assertThat(result.getSegments()).extracting(TransitDto.RouteSegment::getDurationMinutes)
                .containsExactly(2, 39, 2);
        assertThat(result.getTotalDurationMinutes()).isEqualTo(53);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fillsOnlyMissingEndpoint(boolean hasAccess) {
        expectTransit(route(hasAccess ? WALK + "," + BUS : BUS + "," + WALK));
        if (hasAccess) expectWalk(ALIGHT_X, ALIGHT_Y, EX, EY, 452);
        else expectWalk(SX, SY, BOARD_X, BOARD_Y, 378);
        assertThat(search().get(0).getSegments()).extracting(TransitDto.RouteSegment::getDurationMinutes)
                .containsExactly(hasAccess ? 2 : 7, 39, hasAccess ? 8 : 2);
        server.verify();
    }

    @Test
    void reusesIdenticalWalkQueriesAcrossAlternativesWithinOneSearch() {
        expectTransit(String.join(",", route(BUS), route(BUS), route(BUS)));
        expectWalk(SX, SY, BOARD_X, BOARD_Y, 378);
        expectWalk(ALIGHT_X, ALIGHT_Y, EX, EY, 452);
        assertThat(search()).hasSize(3).allSatisfy(r -> assertThat(r.getSegments()).hasSize(3));
        server.verify();
    }

    @Test
    void doesNotInventWalkForCoordinatesAtTheStop() {
        expectTransit(route(BUS));
        var result = client.search(BOARD_X, BOARD_Y, ALIGHT_X, ALIGHT_Y).get(0);
        assertThat(result.getSegments()).hasSize(1);
        server.verify();
    }

    @Test
    void keepsIntermediateWalkingStepWhenAddingEndpointWalks() {
        expectTransit(route(BUS + "," + WALK + "," + BUS));
        expectWalk(SX, SY, BOARD_X, BOARD_Y, 378);
        expectWalk(ALIGHT_X, ALIGHT_Y, EX, EY, 452);
        assertThat(search().get(0).getSegments()).extracting(TransitDto.RouteSegment::getTransitType)
                .containsExactly("WALK", "BUS", "WALK", "BUS", "WALK");
        server.verify();
    }

    @Test
    void unavailableWalkingApiDoesNotReturnAnIncompleteRoute() {
        expectTransit(route(BUS));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v2/routing/walk"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertError(ErrorCode.TRANSIT_API_UNAVAILABLE);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken-json", "null", "{\"status\":\"ROUTE_RESULT_NOT_FOUND\"}",
            "{\"status\":\"OK\",\"route\":{\"properties\":{}}}",
            "{\"status\":\"OK\",\"route\":{\"properties\":{\"totalTime\":-1}}}"})
    void invalidWalkingResponseDoesNotSilentlyBecomeZeroMinutes(String body) {
        expectTransit(route(BUS));
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v2/routing/walk"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertError(ErrorCode.TRANSIT_INVALID_RESPONSE);
        server.verify();
    }

    @Test
    void missingStopCoordinatesDoNotSilentlyBecomeZeroMinutes() {
        expectTransit(route(BUS.replace("[126.97438543,37.55890357]", "[null,null]")));
        assertError(ErrorCode.TRANSIT_INVALID_RESPONSE);
        server.verify();
    }

    private List<TransitDto.RouteOptionResponse> search() { return client.search(SX, SY, EX, EY); }

    private String route(String steps) {
        return "{\"properties\":{\"totalTime\":3140,\"transfers\":0,\"fare\":{\"value\":3000}},\"steps\":[" + steps + "]}";
    }

    private void expectTransit(String routes) {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v2/routing/publictraffic"))
                .andRespond(withSuccess("{\"status\":\"OK\",\"routes\":[" + routes + "]}", MediaType.APPLICATION_JSON));
    }

    private void expectWalk(double sx, double sy, double ex, double ey, int seconds) {
        server.expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v2/routing/walk"))
                .andExpect(queryParam("start_x", Double.toString(sx)))
                .andExpect(queryParam("start_y", Double.toString(sy)))
                .andExpect(queryParam("end_x", Double.toString(ex)))
                .andExpect(queryParam("end_y", Double.toString(ey)))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess("{\"status\":\"OK\",\"route\":{\"properties\":{\"totalTime\":"
                        + seconds + "}}}", MediaType.APPLICATION_JSON));
    }

    private void assertError(ErrorCode error) {
        assertThatThrownBy(this::search).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(error));
    }
}
