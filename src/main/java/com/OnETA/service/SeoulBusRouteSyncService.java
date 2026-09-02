package com.OnETA.service;

import com.OnETA.dto.bus.BusRouteApiResponseDto;
import com.OnETA.dto.bus.BusRouteStationApiResponseDto;
import com.OnETA.entity.SeoulBusRoute;
import com.OnETA.repository.SeoulBusRouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeoulBusRouteSyncService {

    private final SeoulBusRouteRepository seoulBusRouteRepository;
    private final RestTemplate restTemplate;

    // application.yml에 저장된 인증키
    @Value("${api.seoul-bus.service-key}")
    private String serviceKey;

    // 노선번호에 해당하는 노선 목록 조회 API
    private static final String BASE_URL = "http://ws.bus.go.kr/api/rest/busRouteInfo/getBusRouteList";

    // 노선별 경유 정류소 조회 API (회차지 파악용)
    private static final String STATION_URL = "http://ws.bus.go.kr/api/rest/busRouteInfo/getStaionByRoute";

    // 전체 노선을 긁어오기 위한 키워드 배열
    private static final String[] SEARCH_KEYWORDS = {
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"
    };

//    // 디버깅 전용 배열, 배포 시 수정 예정
//    private static final String[] TEST_SEARCH_KEYWORDS = {
//            "0", "1"
//    };

    // 애플리케이션 실행 완료 직후 1회 즉시 실행 + 이후 매주 일요일 오전 2시 실행
    // @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 0 2 ? * SUN", zone = "Asia/Seoul")
    @Transactional
    public void syncSeoulBusRoutes() {
        log.info("서울시 버스 노선 목록 동기화를 시작합니다.");

        // 중복 제거를 위한 Map (Key: 노선ID, Value: Entity)
        Map<String, SeoulBusRoute> apiRoutesMap = new HashMap<>();

        for (String keyword : SEARCH_KEYWORDS) {
            try {
                // 키워드만 명시적으로 UTF-8 인코딩
                String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8.toString());

                URI uri = UriComponentsBuilder.fromUriString(BASE_URL)
                        .queryParam("ServiceKey", serviceKey)
                        .queryParam("strSrch", encodedKeyword)
                        .queryParam("resultType", "json")
                        .build(true) // true: 파라미터들이 이미 인코딩되어 있으니 추가 인코딩 X
                        .toUri();

                // ** [서울특별시_노선정보조회 서비스] API 호출 **
                BusRouteApiResponseDto response = restTemplate.getForObject(uri, BusRouteApiResponseDto.class);

                // 파싱 및 Map에 담기 (중복 시 덮어쓰기 됨)
                if (response != null && response.getMsgBody() != null && response.getMsgBody().getItemList() != null) {
                    List<BusRouteApiResponseDto.ItemList> items = response.getMsgBody().getItemList();

                    for (BusRouteApiResponseDto.ItemList item : items) {
                        SeoulBusRoute route = new SeoulBusRoute(
                                item.getBusRouteId(),
                                item.getBusRouteNm(),
                                item.getStStationNm(),
                                item.getEdStationNm(),
                                item.getTerm(),
                                null
                        );
                        apiRoutesMap.put(route.getRouteId(), route);
                    }
                }

                // 공공 API 호출 제한 방지(Rate Limit)를 위한 딜레이
                Thread.sleep(100);

            } catch (Exception e) {
                log.error("키워드 '{}' 검색 중 오류 발생: {}", keyword, e.getMessage());
            }
        }

        // DB에 존재하는 기존 전체 노선을 1번의 쿼리로 가져옴
        List<SeoulBusRoute> existingRoutes = seoulBusRouteRepository.findAll();

        // 검색을 위해 DB의 기존 전체 노선을 List -> Map 변환
        Map<String, SeoulBusRoute> existingRoutesMap = new HashMap<>();
        for (SeoulBusRoute route : existingRoutes) {
            existingRoutesMap.put(route.getRouteId(), route);
        }

        List<SeoulBusRoute> newRoutes = new ArrayList<SeoulBusRoute>();

        // Insert 리스트와 Update 로직 분리, newRoutes는 Insert 리스트
        // API(apiRoutesMap)로 가져온 노선ID 가 기존 DB(existingRoutesMap)에 존재하면 setter 로 Update
        // 존재하지 않으면 newRoutes 에 객체 추가
        for (SeoulBusRoute apiRoutes : apiRoutesMap.values()) {
            // API로 가져온 노선 객체가 기존 DB에 존재하는지 검색
            SeoulBusRoute existing = existingRoutesMap.get(apiRoutes.getRouteId());

            if (existing != null) {
                // 기존에 있는 노선이면 값만 업데이트 (JPA 더티 체킹에 의해 자동 Update 쿼리 발생)
                existing.setRouteNm(apiRoutes.getRouteNm());
                existing.setStartPoint(apiRoutes.getStartPoint());
                existing.setEndPoint(apiRoutes.getEndPoint());
                existing.setTerm(apiRoutes.getTerm());

                // 기존 노선이지만 회차지 순번 정보가 없는 경우에만 API 호출
                if (existing.getTurnaroundSeq() == null) {
                    existing.setTurnaroundSeq(fetchTurnaroundSeq(existing.getRouteId()));
                }
            } else {
                // 새로운 노선인 경우 회차지 순번을 API로 조회
                apiRoutes.setTurnaroundSeq(fetchTurnaroundSeq(apiRoutes.getRouteId()));
                newRoutes.add(apiRoutes);
            }
        }

        // 새로운 노선들만 saveAll() 호출 (Bulk Insert 동작)
        if (!newRoutes.isEmpty()) {
            seoulBusRouteRepository.saveAll(newRoutes);
        }

        log.info("버스 노선 동기화 완료. 신규 추가: {}건, 업데이트 검사 완료: {}건",
                newRoutes.size(), existingRoutesMap.size());
    }


    // 특정 노선의 전체 정류장을 조회하여 '회차지' 정류장의 seq 값 반환
    private Integer fetchTurnaroundSeq(String routeId) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(STATION_URL)
                    .queryParam("ServiceKey", serviceKey)
                    .queryParam("busRouteId", routeId)
                    .queryParam("resultType", "json")
                    .build(true)
                    .toUri();

            // ** [서울특별시_노선정보조회 서비스] API 호출 **
            BusRouteStationApiResponseDto response = restTemplate.getForObject(uri, BusRouteStationApiResponseDto.class);

            if (response != null && response.getMsgBody() != null && response.getMsgBody().getItemList() != null) {
                for (BusRouteStationApiResponseDto.ItemList item : response.getMsgBody().getItemList()) {
                    // 회차지 정류장인 경우 해당 순번 반환
                    if ("Y".equals(item.getTransYn())) {
                        return Integer.parseInt(item.getSeq());
                    }
                }
            }

            // API 호출 제한 방지용 딜레이
            Thread.sleep(100);

        } catch (Exception e) {
            log.error("노선 ID {} 회차지 정류장 순번 조회 중 오류 발생: {}", routeId, e.getMessage());
        }

        return null; // 회차지가 명시되지 않은 편도 노선 등의 경우 null 반환
    }

}