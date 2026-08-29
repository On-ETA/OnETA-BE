package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.bus.DepotNotificationRequestDto;
import com.OnETA.dto.bus.DepotNotificationResponseDto;
import com.OnETA.entity.BusDirection;
import com.OnETA.entity.DepotNotification;
import com.OnETA.entity.User;
import com.OnETA.entity.UserBus;
import com.OnETA.repository.DepotNotificationRepository;
import com.OnETA.repository.SeoulBusRouteRepository;
import com.OnETA.repository.UserBusRepository;
import com.OnETA.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepotNotificationService {

    private final FcmService fcmService;
    private final UserRepository userRepository;
    private final UserBusRepository userBusRepository;
    private final DepotNotificationRepository depotNotificationRepository;
    private final SeoulBusRouteRepository seoulBusRouteRepository;

    // 맞춤 알림 페이지::차고지 출발 알림 리스트 조회
    @Transactional(readOnly = true)
    public List<DepotNotificationResponseDto> getMyDepotNotifications(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "사용자를 찾을 수 없습니다."));

        List<DepotNotification> notifications = depotNotificationRepository.findAllByUserBus_User(user);

        return notifications.stream()
                .map(DepotNotificationResponseDto::from)
                .toList();
    }

    // 출고지 알림 켜기(UserBus + DepotNotification 동시에 Repo 에 추가)
    @Transactional
    public void setDepotNotification(String email, DepotNotificationRequestDto request){

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "사용자를 찾을 수 없습니다."));

        if (!seoulBusRouteRepository.existsById(request.getRouteId())) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "존재하지 않거나 지원하지 않는 버스 노선입니다.");
        }

        // 이미 등록해둔 UserBus 인지 먼저 확인 (동일 routeId & 동일 direction 기준)
        Optional<UserBus> existingUserBus = userBusRepository.findByUserAndRouteIdAndDirection(
                user, request.getRouteId(), request.getDirection()
        );

        // 새로 등록하는 UserBus 인 경우, 5개 등록 제한 검사
        if (existingUserBus.isEmpty()) {
            List<UserBus> currentBuses = userBusRepository.findAllByUser(user);
            if (currentBuses.size() >= 5) {
                throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "차고지 알림은 최대 5개까지만 등록 가능합니다.");
            }
        }

        // 없으면 새로 저장, 있으면 기존 객체 사용
        UserBus targetBus = existingUserBus.orElseGet(() -> {
            UserBus newBus = UserBus.builder()
                    .user(user)
                    .routeId(request.getRouteId())
                    .busNumber(request.getBusNumber())
                    .direction(request.getDirection())
                    .directionName(request.getDirectionName())
                    .build();
            return userBusRepository.save(newBus);
        });

        // 해당 버스의 출고지 알림 설정 찾기 또는 새로 만들기
        DepotNotification notification = depotNotificationRepository.findByUserBus(targetBus)
                .orElseGet(() -> {
                    DepotNotification newNotification = DepotNotification.builder()
                            .userBus(targetBus)
                            .active(true) // UserBus 추가와 동시에 활성화
                            .build();
                    return depotNotificationRepository.save(newNotification);
                });

        // 기존 엔티티가 존재했는데 false 인 경우에만 변경 (덮어씌우기)
        if (!notification.isActive()) {
            notification.enableNotification();
        }
    }

    // 출고지 알림 삭제 (DepotNotification 삭제가 아닌 UserBus 자체를 삭제하여 Cascade 연쇄 삭제)
    @Transactional
    public void deleteDepotNotification(String email, Long userBusId) {

        UserBus userBus = userBusRepository.findById(userBusId)
                .orElseThrow(() -> new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "등록된 버스 정보를 찾을 수 없습니다."));

        // 본인의 버스인지 확인
        if (!userBus.getUser().getEmail().equals(email)) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "삭제 권한이 없습니다.");
        }

        // UserBus를 삭제 시 UserBus 엔티티의 CascadeType.ALL 에 의해 DepotNotification 엔티티 자동 삭제
        userBusRepository.delete(userBus);
    }

    // 시스템 내부용, 버스가 출고지에서 출발했을 때 호출되는 메서드
    // 외부 공공데이터 API 폴링(Polling) 로직에서 출고를 감지하면 이 함수를 호출
    @Transactional
    public void triggerDepotDeparture(String routeId, BusDirection busDirection, String plainNo){
        // 해당 노선의 출고지 알림을 켜둔(active = true) 모든 사용자를 리포지토리에서 검색
        List<DepotNotification> activeNotifications = depotNotificationRepository
                .findByUserBus_RouteIdAndUserBus_DirectionAndActiveTrue(routeId, busDirection);

        for(DepotNotification notification : activeNotifications){

            String targetEmail = notification.getUserBus().getUser().getEmail();
            String routeBusNumber = notification.getUserBus().getBusNumber();       // 예: "144"
            String directionName = notification.getUserBus().getDirectionName();    // 예: "강남역 방면"

            String pushTitle = (busDirection == BusDirection.TURNAROUND) ? "차고지를 출발" : "회차지를 출발";
            String pushBody = String.format("[%s 방면] %s번 버스가 방금 %s했어요!", directionName, routeBusNumber, pushTitle);

            // 실제 푸시 발송
            fcmService.sendPush(targetEmail, pushTitle, pushBody);

            log.info("[푸시 발송 완료 처리] 사용자: {}, 내용: {} (감지된 차량번호: {})", targetEmail, pushBody, plainNo);

            // 알림 1회 발송 후 자동 false 업데이트
            notification.disableNotification();
        }
    }

}
