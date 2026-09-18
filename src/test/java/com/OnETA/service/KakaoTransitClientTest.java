package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoTransitClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
    private final KakaoTransitClient kakao = new KakaoTransitClient(mapper, http, "test-key", true);
    private final PublicDataTransitService realtime = mock(PublicDataTransitService.class);
    private final TransitApiService service = new TransitApiService(realtime, mapper, http);
    private static final String ROUTE = """
            {"properties":{"totalTime":22204,"transfers":3},"steps":[
              {"properties":{"type":"WALKING","time":61}},
              {"properties":{"type":"BUS","time":21900,"stops":[{"name":"호남제일문"},{"name":"신갈"}],
                "vehicles":[{"name":"8543","type":"시외"}]},
               "path":{"points":[[127.1,35.9],[127.2,37.2]]}},
              {"properties":{"type":"SUBWAY","time":243,"stops":[{"name":"강남"},{"name":"역삼"}],
                "vehicles":[{"name":"2호선"}]}}]}
            """;
    private static final String BODY = "{\"status\":\"OK\",\"routes\":[" + ROUTE + "]}";

    private void configure() {
        ReflectionTestUtils.setField(service, "odsayApiKey", "odsay-key");
        ReflectionTestUtils.setField(service, "odsayApiUrl", "https://api.odsay.com/v1/api/searchPubTransPathR");
        ReflectionTestUtils.setField(service, "kakaoTransitClient", kakao);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"error\":[{\"code\":\"429\"}]}", "broken-json",
            "{\"result\":{\"path\":[]}}", "{\"result\":{\"searchType\":1}}"})
    void fallsBackAndPreservesSavedRouteWithoutRealtimeCalls(String odsay) {
        configure();
        server.expect(queryParam("SX", "127.1")).andRespond(withSuccess(odsay, MediaType.APPLICATION_JSON));
        server.expect(queryParam("start_x", "127.1")).andExpect(queryParam("end_y", "37.2"))
                .andExpect(header("Authorization", "KakaoAK test-key"))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));
        var route = service.searchRoutes(127.1, 35.9, 127.2, 37.2).get(0);
        assertThat(route.getProvider()).isEqualTo("KAKAO");
        assertThat(route.getRouteId()).startsWith("KAKAO_");
        assertThat(route.getTotalDurationMinutes()).isEqualTo(371);
        assertThat(route.getTransferCount()).isEqualTo(3);
        assertThat(route.getTotalCost()).isNull();
        assertThat(route.getSegments()).extracting(TransitDto.RouteSegment::getTransitType)
                .containsExactly("WALK", "BUS", "SUBWAY");
        var bus = route.getSegments().get(1);
        assertThat(bus.getTransitName()).isEqualTo("8543");
        assertThat(bus.getStartStation()).isEqualTo("호남제일문");
        assertThat(bus.getStartX()).isEqualTo(127.1);
        assertThat(bus.getOdsayRouteId()).isNull();
        assertThat(bus.getRealTimeSource()).isNull();
        assertThat(service.getRealTimeDuration(mapper.writeValueAsString(route))).isEqualTo(371);
        verifyNoInteractions(realtime);
        server.verify();
    }

    @Test
    void networkFailureFallsBackAndBothProviderFailureIsUnavailable() {
        configure();
        server.expect(queryParam("SX", "127.1"))
                .andRespond(withException(new java.net.SocketTimeoutException("secret-url")));
        server.expect(queryParam("start_x", "127.1")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> service.searchRoutes(127.1, 35.9, 127.2, 37.2))
                .isInstanceOfSatisfying(GlobalException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_API_UNAVAILABLE));
        server.verify();
    }

    @Test
    void invalidInputDoesNotCallEitherProvider() {
        configure();
        assertThatThrownBy(() -> service.searchRoutes(Double.NaN, 35.9, 127.2, 37.2))
                .isInstanceOf(GlobalException.class);
        server.verify();
    }

    @Test
    void invalidOdsayCoordinatesDoNotTriggerFallback() {
        configure();
        server.expect(queryParam("SX", "127.1"))
                .andRespond(withSuccess("{\"error\":{\"code\":\"-8\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.searchRoutes(127.1, 35.9, 127.2, 37.2))
                .isInstanceOfSatisfying(GlobalException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        server.verify();
    }

    @Test
    void odsaySuccessDoesNotCallKakao() {
        configure();
        server.expect(queryParam("SX", "127.1")).andRespond(withSuccess(
                "{\"result\":{\"path\":[{\"info\":{\"totalTime\":5},\"subPath\":[{\"trafficType\":3,\"sectionTime\":5}]}]}}",
                MediaType.APPLICATION_JSON));
        assertThat(service.searchRoutes(127.1, 35.9, 127.2, 37.2).get(0).getProvider()).isEqualTo("ODSAY");
        server.verify();
    }

    @Test
    void disabledClientPreservesOdsayError() {
        configure();
        ReflectionTestUtils.setField(service, "kakaoTransitClient", new KakaoTransitClient(mapper, http, "", true));
        server.expect(queryParam("SX", "127.1")).andRespond(withSuccess("broken", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.searchRoutes(127.1, 35.9, 127.2, 37.2))
                .isInstanceOfSatisfying(GlobalException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_INVALID_RESPONSE));
        assertThat(new KakaoTransitClient(mapper, http, "key", false).isConfigured()).isFalse();
        server.verify();
    }

    @Test
    void limitsCandidatesAndMapsFareAndStableIds() {
        String route = ROUTE.replace("\"transfers\":3", "\"transfers\":3,\"fare\":{\"min\":1550,\"max\":1650}");
        String body = "{\"status\":\"OK\",\"routes\":[" + String.join(",", route, route, route, route) + "]}";
        var parsed = kakao.parse(body);
        assertThat(parsed).hasSize(3);
        assertThat(parsed.get(0).getTotalCost()).isEqualTo(1550);
        assertThat(parsed.get(0).getRouteId()).isEqualTo(kakao.parse(body).get(0).getRouteId());
        assertThat(kakao.parse(body.replace("\"min\":1550,\"max\":1650", "\"value\":1600"))
                .get(0).getTotalCost()).isEqualTo(1600);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "broken", "{}", "{\"status\":\"OK\"}",
            "{\"status\":\"OK\",\"routes\":[{}]}"})
    void rejectsMalformedResponses(String body) {
        assertThatThrownBy(() -> kakao.parse(body)).isInstanceOfSatisfying(GlobalException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_INVALID_RESPONSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_RESULTS", "STARTNODES_NULL", "ENDNODES_NULL"})
    void reportsNoRoute(String status) {
        assertThatThrownBy(() -> kakao.parse("{\"status\":\"" + status + "\"}"))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TRANSIT_ROUTE_NOT_FOUND));
    }
}
