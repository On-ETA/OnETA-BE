package com.HomeRun.service;

import com.HomeRun.dto.TransitDto;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TransitApiServiceTest {

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
                           "lane":[{"busNo":"741","busID":55,"busCityCode":1000,
                                    "busLocalBlID":"123000010"}]}
                        ]}]}}
                        """, MediaType.APPLICATION_JSON));

        TransitDto.RouteOptionResponse route =
                service.searchRoutes(127.0, 36.3, 127.2, 36.5).get(0);

        assertThat(route.getRouteId()).startsWith("ROUTE_");
        assertThat(route.getRealTimeDurationMinutes()).isEqualTo(19);
        assertThat(route.getSegments().get(1).getLocalStationId()).isEqualTo("111000931");
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
}
