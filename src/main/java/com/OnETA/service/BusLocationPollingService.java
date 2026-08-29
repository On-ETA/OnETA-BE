package com.OnETA.service;

import com.OnETA.dto.bus.BusLocationResponseDto;
import com.OnETA.entity.BusDirection;
import com.OnETA.repository.DepotNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusLocationPollingService {

    private final DepotNotificationRepository depotNotificationRepository;
    private final DepotNotificationService depotNotificationService; // 알림 발송 로직 포함

    // API(getBusPosByRtid) 정보 파싱을 SeoulBusLocationService::getRealTimeBusLocations 에서 진행
    private final SeoulBusLocationService seoulBusLocationService;

    // 1분마다 실행, 차고지 및 회차지 출발을 감지
    @Scheduled(fixedDelay = 60000)
    public void pollBusLocations() {
        // 현재 알림 대기 중인 노선 ID만 조회
        List<String> activeRouteIds = depotNotificationRepository.findDistinctActiveRouteIds();

        if (activeRouteIds.isEmpty()) { return; } // 켜져있는 알림이 없으면 호출 생략

        // 활성화된 노선들에 대해서 실시간 위치 확인
        for (String routeId : activeRouteIds) {
            try {
                // LocationService를 활용하여 상태를 가져옴
                BusLocationResponseDto locationInfo = seoulBusLocationService.getRealTimeBusLocations(routeId);

                // 차고지 출발 감지 및 알림 트리거
                if (locationInfo.isDepotDeparted()) {
                    log.info("노선 {}의 차고지 출발 감지. 차량번호: {}", routeId, locationInfo.getDepotDepartedBusNo());

                    // triggerDepotDeparture 메서드가 BusDirection을 파라미터로 받으므로 그대로 활용
                    depotNotificationService.triggerDepotDeparture(
                            routeId,
                            BusDirection.TURNAROUND,
                            locationInfo.getDepotDepartedBusNo()
                    );
                }

                // 회차지 출발 감지 및 알림 트리거
                if (locationInfo.isTurnaroundDeparted()) {
                    log.info("노선 {}의 회차지 출발 감지. 차량번호: {}", routeId, locationInfo.getTurnaroundDepartedBusNo());

                    depotNotificationService.triggerDepotDeparture(
                            routeId,
                            BusDirection.DEPOT,
                            locationInfo.getTurnaroundDepartedBusNo()
                    );
                }

                // 공공 API 호출 제한 방어
                Thread.sleep(500);

            } catch (Exception e) {
                log.error("노선 {} 실시간 위치 조회/알림 스케줄링 중 에러 발생: {}", routeId, e.getMessage());
            }
        }
    }
}