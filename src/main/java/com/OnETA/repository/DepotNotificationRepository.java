package com.OnETA.repository;

import com.OnETA.entity.BusDirection;
import com.OnETA.entity.DepotNotification;
import com.OnETA.entity.User;
import com.OnETA.entity.UserBus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.List;

public interface DepotNotificationRepository extends JpaRepository<DepotNotification, Long> {
    // 조인을 통해 특정 사용자의 알림 목록만 가져오기
    List<DepotNotification> findAllByUserBus_User(User user);

    // 특정 BusDirection의 UserBus의 DepotNotification 엔티티 검색 메서드
    Optional<DepotNotification> findByUserBus(UserBus userBus);

    // 특정 노선 AND 특정 방면 AND 활성화된 알림만 조회
    List<DepotNotification> findByUserBus_RouteIdAndUserBus_DirectionAndActiveTrue(
            String routeId, BusDirection direction);

    // 현재 알림이 켜져 있는(active = true) '고유한 노선 ID 목록'만 가져오기 위한 쿼리
    // 스케줄러가 이 목록을 보고 외부 API를 최소한으로 호출함
    @Query("SELECT DISTINCT d.userBus.routeId FROM DepotNotification d WHERE d.active = true")
    List<String> findDistinctActiveRouteIds();
}