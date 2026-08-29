package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.bus.BusLocationResponseDto;
import com.OnETA.dto.bus.BusPosApiResponseDto;
import com.OnETA.entity.SeoulBusRoute;
import com.OnETA.repository.SeoulBusRouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeoulBusLocationService {

    private final RestTemplate restTemplate;
    private final SeoulBusRouteRepository seoulBusRouteRepository;

    @Value("${api.seoul-bus.service-key}")
    private String serviceKey;

    private static final String BASE_URL = "http://ws.bus.go.kr/api/rest/buspos/getBusPosByRtid";

    // 특정 노선 ID의 실시간 버스 위치들을 받아온 BusPosApiResponseDto 타입 response 속 ItemList 를
    // BusLocationResponseDto.BusPosition 타입 객체 리스트로 파싱
    // 해당 객체 리스트(BusLocationResponseDto.BusPosition) 중 각 객체의 메소드(isDepotDeparted, isTurnaroundDeparted)를 통해 필터링 한 뒤
    // 필터링 한 객체 나열(BusLocationResponseDto.BusPosition)들 속 각각에서 차량번호만 빼낸 String 타입 나열을 만든 뒤
    // 가장 첫 번째 String 데이터를 지역변수에 저장
    @Transactional(readOnly = true)
    public BusLocationResponseDto getRealTimeBusLocations(String routeId) {

        // 서울버스DB 노선 정보(turnaroundSeq) 가져오기
        SeoulBusRoute route = seoulBusRouteRepository.findById(routeId)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "해당 노선 정보를 찾을 수 없습니다."));

        Integer turnaroundSeq = route.getTurnaroundSeq();

        try {

            URI uri = UriComponentsBuilder.fromUriString(BASE_URL)
                    .queryParam("ServiceKey", serviceKey)
                    .queryParam("busRouteId", routeId)
                    .queryParam("resultType", "json")
                    .build(true)
                    .toUri();

            // *** [서울특별시_버스위치정보조회 서비스] API 호출 ***
            BusPosApiResponseDto response = restTemplate.getForObject(uri, BusPosApiResponseDto.class);

            // 운행 중인 버스가 없는 경우
            if (response == null || response.getMsgBody() == null || response.getMsgBody().getItemList() == null) {
                return BusLocationResponseDto.builder()
                        .routeId(routeId)
                        .depotDeparted(false)
                        .turnaroundDeparted(false)
                        .activeBuses(new ArrayList<>())
                        .build();
            }

            // API 데이터 파싱
            List<BusLocationResponseDto.BusPosition> activeBuses = response.getMsgBody().getItemList().stream()
                    .map(item -> BusLocationResponseDto.BusPosition.builder()
                            .plainNo(item.getPlainNo())
                            .sectOrd(Integer.parseInt(item.getSectOrd()))
                            .atStop("1".equals(item.getStopFlag()))
                            .build())
                    .toList();

            // 차고지 출발 차량 찾기
            String depotBusNo = activeBuses.stream()
                    .filter(BusLocationResponseDto.BusPosition::isDepotDeparted)
                    .map(BusLocationResponseDto.BusPosition::getPlainNo)
                    .findFirst()
                    .orElse(null);

            // 회차지 출발 차량 찾기
            String turnaroundBusNo = activeBuses.stream()
                    .filter(bus -> bus.isTurnaroundDeparted(turnaroundSeq))
                    .map(BusLocationResponseDto.BusPosition::getPlainNo)
                    .findFirst()
                    .orElse(null);

            return BusLocationResponseDto.builder()
                    .routeId(routeId)
                    .depotDeparted(depotBusNo != null)
                    .depotDepartedBusNo(depotBusNo)
                    .turnaroundDeparted(turnaroundBusNo != null)
                    .turnaroundDepartedBusNo(turnaroundBusNo)
                    .activeBuses(activeBuses)
                    .build();

        } catch (Exception e) {
            log.error("노선 ID {} 실시간 위치 조회 중 에러 발생: {}", routeId, e.getMessage());
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR, "실시간 버스 위치를 가져오는데 실패했습니다.");
        }
    }
}