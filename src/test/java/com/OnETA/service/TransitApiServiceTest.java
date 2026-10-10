package com.OnETA.service;

import com.OnETA.dto.BusType;
import com.OnETA.dto.TransitDto;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TransitApiServiceTest {

    @Test
    void scheduleSearchIncludesExactOriginAndDestinationCoordinatesNotStationCoordinates() {
        RestTemplate client = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(client).build();
        TransitApiService service = new TransitApiService(mock(PublicDataTransitService.class),
                new ObjectMapper(), client);
        ReflectionTestUtils.setField(service, "odsayApiKey", "test-key");
        ReflectionTestUtils.setField(service, "odsayApiUrl", "https://api.odsay.com/v1/api/searchPubTransPathR");
        server.expect(queryParam("SX", "126.92463186895164"))
                .andRespond(withSuccess("""
                        {"result":{"path":[{"info":{"totalTime":34,"payment":0,"transitCount":0},
                         "subPath":[
                           {"trafficType":3,"sectionTime":11},
                           {"trafficType":2,"sectionTime":12,"startName":"서교동","endName":"신도림역",
                            "startX":126.92,"startY":37.54,"endX":126.89,"endY":37.51,
                            "lane":[{"busNo":"N51","busID":123,"type":11}]},
                           {"trafficType":3,"sectionTime":11}
                         ]}]}}
                        """, MediaType.APPLICATION_JSON));

        var results = service.searchScheduleCandidates("test@example.com",
                126.92463186895164, 37.550164265498864, "출발지",
                126.88852, 37.50975, "목적지", 10);

        assertThat(results).hasSize(1);
        var route = results.get(0);
        assertThat(route.getOriginX()).isEqualTo(126.92463186895164);
        assertThat(route.getOriginY()).isEqualTo(37.550164265498864);
        assertThat(route.getDestX()).isEqualTo(126.88852);
        assertThat(route.getDestY()).isEqualTo(37.50975);
        // The endpoint coordinates remain independent of transit stop locations.
        assertThat(route.getOriginX()).isNotEqualTo(126.92);
        assertThat(service.readSavedRoute(new ObjectMapper().writeValueAsString(route))
                .getOriginX()).isEqualTo(126.92463186895164);
        server.verify();
    }

    @Test
    void oldWrappedRouteRestoresCoordinatesFromStoredPlaceObjectsNotBusStops() {
        TransitApiService service = new TransitApiService(mock(PublicDataTransitService.class),
                new ObjectMapper(), mock(RestTemplate.class));
        var details = """
                {"route":{"routeId":"SEOUL_NIGHT_old","provider":"SEOUL_NIGHT",
                 "originAddress":"출발지","destinationAddress":"목적지",
                 "segments":[
                    {"transitType":"WALK","durationMinutes":11,"startX":null,"startY":null},
                    {"transitType":"BUS","transitName":"N51","startX":126.92,"startY":37.54,
                     "endX":126.89,"endY":37.51},
                    {"transitType":"WALK","durationMinutes":11,"endX":null,"endY":null}]},
                 "origin":{"label":"출발지","raw":{"x":126.92463186895164,"y":37.550164265498864}},
                 "destination":{"label":"도착지","raw":{"x":126.88852,"y":37.50975}}}
                """;
        var result = service.readSavedRoute(details);
        assertThat(result.getOriginX()).isEqualTo(126.92463186895164);
        assertThat(result.getOriginY()).isEqualTo(37.550164265498864);
        assertThat(result.getDestX()).isEqualTo(126.88852);
        assertThat(result.getDestY()).isEqualTo(37.50975);
    }

    @Test
    void routeWithOnlyBusStationCoordinatesDoesNotInventAddressCoordinates() {
        TransitApiService service = new TransitApiService(mock(PublicDataTransitService.class),
                new ObjectMapper(), mock(RestTemplate.class));
        var result = service.readSavedRoute("""
                {"routeId":"SEOUL_NIGHT_old","provider":"SEOUL_NIGHT",
                 "segments":[
                    {"transitType":"WALK","durationMinutes":11,"startX":null,"startY":null},
                    {"transitType":"BUS","transitName":"N51","startX":126.92,"startY":37.54,
                     "endX":126.89,"endY":37.51},
                    {"transitType":"WALK","durationMinutes":11,"endX":null,"endY":null}]}
                """);
        assertThat(result.getOriginX()).isNull();
        assertThat(result.getOriginY()).isNull();
        assertThat(result.getDestX()).isNull();
        assertThat(result.getDestY()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "4, METROPOLITAN",
            "14, METROPOLITAN",
            "22, METROPOLITAN",
            "11, TRUNK",
            "12, BRANCH",
            "13, CIRCULAR",
            "3, VILLAGE"
    })
    void normalizesOdsayBusTypes(int providerType, BusType expected) {
        assertThat(BusType.fromOdsay(providerType)).isEqualTo(expected);
    }


    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"error\":[{\"code\":\"429\",\"message\":\"Daily quota exceeded\"}]}|TRANSIT_API_UNAVAILABLE",
            "{\"error\":{\"code\":\"-99\"}}|TRANSIT_ROUTE_NOT_FOUND",
            "{\"error\":[{\"code\":\"-98\"}]}|INVALID_INPUT_VALUE",
            "{\"result\":{\"searchType\":1,\"trainRequest\":{\"count\":1}}}|TRANSIT_ROUTE_UNSUPPORTED",
            "{\"result\":{\"searchType\":2}}|TRANSIT_ROUTE_UNSUPPORTED",
            "{\"result\":{\"path\":[]}}|TRANSIT_ROUTE_NOT_FOUND",
            "{\"result\":{}}|TRANSIT_INVALID_RESPONSE",
            "null|TRANSIT_INVALID_RESPONSE",
            "not-json|TRANSIT_INVALID_RESPONSE",
            "{\"result\":{\"path\":[{\"info\":{},\"subPath\":[{\"trafficType\":4}]}]}}|TRANSIT_ROUTE_UNSUPPORTED"
    })
    void classifiesProviderResponses(String body, ErrorCode expected) {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TransitApiService service = new TransitApiService(mock(PublicDataTransitService.class),
                new ObjectMapper(), restTemplate);
        ReflectionTestUtils.setField(service, "odsayApiKey", "test-key");
        ReflectionTestUtils.setField(service, "odsayApiUrl", "https://api.odsay.com/v1/api/searchPubTransPathR");
        server.expect(queryParam("SX", "127.05593797036339"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.searchRoutes(127.05593797036339, 35.99330835090628,
                127.060175955621, 37.2042695838843))
                .isInstanceOfSatisfying(GlobalException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
        server.verify();
    }

    @Test
    void sendsOdsayRefererAndCalculatesDurationWithBusWait() {
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        when(publicData.findArrival(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate(
                        "1000", "111000931", "123000010", "12022", 180, "SEOUL_TOPIS"));

        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TransitApiService service =
                new TransitApiService(publicData, new ObjectMapper(), restTemplate);
        ReflectionTestUtils.setField(service, "odsayApiKey", "odsay-key");
        ReflectionTestUtils.setField(service, "odsayApiUrl",
                "https://api.odsay.com/v1/api/searchPubTransPathR");
        ReflectionTestUtils.setField(service, "odsayReferer", "http://localhost:8080");

        server.expect(header("Referer", "http://localhost:8080/"))
                .andExpect(queryParam("apiKey", "odsay-key"))
                .andRespond(withSuccess("""
                        {"result":{"path":[{"info":{"totalTime":20,"payment":1400,"transitCount":0},
                        "subPath":[
                          {"trafficType":3,"sectionTime":4},
                          {"trafficType":2,"sectionTime":12,"startName":"북대전농협",
                           "endName":"대전역","startID":1,"startX":127.1,"startY":36.4,
                           "startStationCityCode":1000,"startLocalStationID":"111000931",
                           "startArsID":"12022","intervalTime":8,
                           "passStopList":{"stations":[
                             {"stationName":"북대전농협","stationID":"1","x":127.1,"y":36.4,"stationArsID":"12022"},
                             {"stationName":"중간 정거장","stationID":"2","x":127.15,"y":36.45},
                             {"stationName":"대전역","stationID":"3","x":127.2,"y":36.5}
                           ]},
                           "lane":[{"busNo":"741","busID":55,"type":11,"busCityCode":1000,
                                    "busLocalBlID":"123000010"}]}
                        ]}]}}
                        """, MediaType.APPLICATION_JSON));

        TransitDto.RouteOptionResponse route =
                service.searchRoutes(127.0, 36.3, 127.2, 36.5).get(0);

        assertThat(route.getRouteId()).startsWith("ROUTE_");
        assertThat(route.getRealTimeDurationMinutes()).isEqualTo(19);
        assertThat(route.getSegments().get(1).getLocalStationId()).isEqualTo("111000931");
        assertThat(route.getSegments().get(1).getBusType()).isEqualTo(BusType.TRUNK);
        assertThat(route.getSegments().get(1).getStations()).extracting(TransitDto.RouteStation::getName)
                .containsExactly("북대전농협", "중간 정거장", "대전역");
        server.verify();
    }

    @Test
    void marksOdsayNightBusByRouteName() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        TransitApiService service = new TransitApiService(
                mock(PublicDataTransitService.class), new ObjectMapper(), restTemplate);
        ReflectionTestUtils.setField(service, "odsayApiKey", "odsay-key");
        ReflectionTestUtils.setField(service, "odsayApiUrl",
                "https://api.odsay.com/v1/api/searchPubTransPathR");

        server.expect(queryParam("SX", "127.0"))
                .andRespond(withSuccess("""
                        {"result":{"path":[{"info":{"totalTime":20,"payment":1400,"transitCount":0},
                        "subPath":[
                          {"trafficType":3,"sectionTime":3},
                          {"trafficType":2,"sectionTime":14,"startName":"강남역","endName":"서울역",
                           "startX":127.0276,"startY":37.4979,
                           "lane":[{"busNo":"N62","busID":55,"type":11}]},
                          {"trafficType":3,"sectionTime":3}
                        ]}]}}
                        """, MediaType.APPLICATION_JSON));

        var route = service.searchScheduleCandidates(127.0, 37.5, 127.1, 37.6, 5).get(0);

        assertThat(route.getSegments().get(1).isNightBus()).isTrue();
        server.verify();
    }

    @Test
    void supplementsExistingNightOnlyPlannerRouteWithIndependentSeoulGraph() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        TransitApiService service = new TransitApiService(
                mock(PublicDataTransitService.class), new ObjectMapper(), http);
        ReflectionTestUtils.setField(service, "odsayApiKey", "odsay-key");
        ReflectionTestUtils.setField(service, "odsayApiUrl",
                "https://api.odsay.com/v1/api/searchPubTransPathR");

        SeoulBusScheduleService seoul = mock(SeoulBusScheduleService.class);
        TransitDto.RouteOptionResponse independent = TransitDto.RouteOptionResponse.builder()
                .routeId("SEOUL_NIGHT_N51").provider("SEOUL_NIGHT").totalDurationMinutes(40)
                .segments(java.util.List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(4).build(),
                        TransitDto.RouteSegment.builder().transitType("BUS")
                                .transitName("N51").nightBus(true).durationMinutes(32).build(),
                        TransitDto.RouteSegment.builder().transitType("WALK").durationMinutes(4).build()))
                .build();
        when(seoul.discoverDirectNightRoutes(126.92555, 37.55087, 126.88852, 37.50975))
                .thenReturn(java.util.List.of(independent));
        ReflectionTestUtils.setField(service, "seoulBusScheduleService", seoul);

        server.expect(queryParam("SX", "126.92555"))
                .andRespond(withSuccess("""
                        {"result":{"path":[{"info":{"totalTime":28,"payment":1400,"transitCount":0},
                        "subPath":[{"trafficType":3,"sectionTime":3},
                        {"trafficType":2,"sectionTime":22,"startName":"홍대입구","endName":"시청",
                        "lane":[{"busNo":"N62","busID":55,"type":11}]},
                        {"trafficType":3,"sectionTime":3}]}]}}
                        """, MediaType.APPLICATION_JSON));

        var results = service.searchScheduleCandidates(126.92555, 37.55087,
                126.88852, 37.50975, 100);

        assertThat(results).extracting(TransitDto.RouteOptionResponse::getProvider)
                .containsExactly("ODSAY", "SEOUL_NIGHT");
        assertThat(results.get(1).getSegments().get(1).getTransitName()).isEqualTo("N51");
        server.verify();
    }

    @Test
    void fallsBackToOdsayDurationWhenRealtimeLookupFails() {
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        when(publicData.findArrival(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);
        TransitApiService service =
                new TransitApiService(publicData, new ObjectMapper(), new RestTemplate());

        TransitDto.RouteOptionResponse route = TransitDto.RouteOptionResponse.builder()
                .totalDurationMinutes(31)
                .segments(java.util.List.of(
                        TransitDto.RouteSegment.builder()
                                .transitType("BUS")
                                .durationMinutes(20)
                                .transitName("5")
                                .startX(127.1)
                                .startY(36.4)
                                .build()))
                .build();

        assertThat(service.enrichWithRealTimeArrivals(route).getRealTimeDurationMinutes())
                .isEqualTo(31);
    }

    @Test
    void recalculatesSavedRouteDetailsForNotificationScheduler() throws Exception {
        PublicDataTransitService publicData = mock(PublicDataTransitService.class);
        when(publicData.findArrival(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PublicDataTransitService.ArrivalEstimate(
                        "1000", "111000931", "123000010", "12022", 300, "SEOUL_TOPIS"));
        ObjectMapper objectMapper = new ObjectMapper();
        TransitApiService service =
                new TransitApiService(publicData, objectMapper, new RestTemplate());

        TransitDto.RouteOptionResponse savedRoute = TransitDto.RouteOptionResponse.builder()
                .totalDurationMinutes(30)
                .segments(java.util.List.of(
                        TransitDto.RouteSegment.builder()
                                .transitType("WALK")
                                .durationMinutes(5)
                                .build(),
                        TransitDto.RouteSegment.builder()
                                .transitType("BUS")
                                .durationMinutes(20)
                                .transitName("5")
                                .localCityCode("1000")
                                .localRouteId("123000010")
                                .localStationId("111000931")
                                .arsId("12022")
                                .build()))
                .build();

        int duration = service.getRealTimeDuration(
                objectMapper.writeValueAsString(savedRoute));

        assertThat(duration).isEqualTo(35);
    }

    @Test
    void scheduleCandidateSearchCanReturnFiveRoutesWithoutRealtimeEnrichment() {
        PublicDataTransitService realtime = mock(PublicDataTransitService.class);
        ObjectMapper mapper = new ObjectMapper();
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
        TransitApiService service = new TransitApiService(realtime, mapper, http);
        ReflectionTestUtils.setField(service, "odsayApiKey", "test");
        ReflectionTestUtils.setField(service, "odsayApiUrl", "https://api.odsay.com/v1/api/searchPubTransPathR");

        String path = "{\"info\":{\"totalTime\":5,\"payment\":0,\"transitCount\":0},"
                + "\"subPath\":[{\"trafficType\":3,\"sectionTime\":5}]}";
        String body = "{\"result\":{\"path\":[" + String.join(",", path, path, path, path, path, path) + "]}}";
        server.expect(requestTo(org.hamcrest.Matchers.containsString("searchPubTransPathR")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(service.searchScheduleCandidates(127.0, 37.5, 127.1, 37.6, 5)).hasSize(5);
        verifyNoInteractions(realtime);
        server.verify();
    }

}
