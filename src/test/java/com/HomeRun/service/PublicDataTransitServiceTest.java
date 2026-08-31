package com.HomeRun.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PublicDataTransitServiceTest {

    @Test
    void matchesOdsayLocalStationToSeoulArrival() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PublicDataTransitService service = new PublicDataTransitService(new ObjectMapper(), restTemplate);
        ReflectionTestUtils.setField(service, "publicDataApiKey", "abc123");
        ReflectionTestUtils.setField(service, "seoulApiUrl", "http://ws.bus.go.kr");

        server.expect(requestTo(org.hamcrest.Matchers.containsString("getArrInfoByRouteAll")))
                .andExpect(queryParam("serviceKey", "abc123"))
                .andExpect(queryParam("busRouteId", "123000010"))
                .andRespond(withSuccess("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <ServiceResult>
                          <msgHeader><headerCd>0</headerCd><headerMsg>정상 처리되었습니다.</headerMsg></msgHeader>
                          <msgBody>
                            <itemList><stId>111000100</stId><arsId>12001</arsId><exps1>100</exps1></itemList>
                            <itemList><stId>111000931</stId><arsId>12022</arsId><exps1>480</exps1></itemList>
                          </msgBody>
                        </ServiceResult>
                        """, MediaType.APPLICATION_XML));

        PublicDataTransitService.ArrivalEstimate result =
                service.findArrival("1000", "123000010", "111000931", "12022",
                        "741", "북가좌삼거리", 126.9, 37.5);

        assertThat(result).isNotNull();
        assertThat(result.stationId()).isEqualTo("111000931");
        assertThat(result.routeId()).isEqualTo("123000010");
        assertThat(result.arrivalSeconds()).isEqualTo(480);
        assertThat(result.source()).isEqualTo("SEOUL_TOPIS");
        server.verify();
    }

    @Test
    void ignoresNonSeoulRoutes() {
        PublicDataTransitService service = new PublicDataTransitService(new ObjectMapper(), new RestTemplate());
        assertThat(service.findArrival("2000", "route", "station", "ars",
                "1", "station", null, null)).isNull();
    }

    @Test
    void fallsBackToTagoByCoordinateAndRouteNumber() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        PublicDataTransitService service = new PublicDataTransitService(new ObjectMapper(), restTemplate);
        ReflectionTestUtils.setField(service, "publicDataApiKey", "abc123");
        ReflectionTestUtils.setField(service, "tagoApiUrl", "https://apis.data.go.kr");

        server.expect(requestTo(org.hamcrest.Matchers.containsString("getCrdntPrxmtSttnList")))
                .andExpect(queryParam("gpsLati", "37.5"))
                .andExpect(queryParam("gpsLong", "126.9"))
                .andRespond(withSuccess("""
                        {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                        "body":{"items":{"item":[{"citycode":"11","nodeid":"SEOUL123",
                        "nodenm":"북가좌삼거리","gpslong":126.9,"gpslati":37.5}]}}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.containsString(
                        "getSttnAcctoArvlPrearngeInfoList")))
                .andExpect(queryParam("cityCode", "11"))
                .andExpect(queryParam("nodeId", "SEOUL123"))
                .andRespond(withSuccess("""
                        {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
                        "body":{"items":{"item":[{"routeid":"SEOUL741","routeno":"741",
                        "arrtime":240}]}}}}
                        """, MediaType.APPLICATION_JSON));

        PublicDataTransitService.ArrivalEstimate result = service.findArrival(
                "2000", "123000010", "111000931", "12022",
                "741", "북가좌삼거리", 126.9, 37.5);

        assertThat(result).isNotNull();
        assertThat(result.source()).isEqualTo("TAGO");
        assertThat(result.arrivalSeconds()).isEqualTo(240);
        server.verify();
    }
}
