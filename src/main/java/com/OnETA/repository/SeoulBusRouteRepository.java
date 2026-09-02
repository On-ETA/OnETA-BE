package com.OnETA.repository;

import com.OnETA.entity.SeoulBusRoute;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SeoulBusRouteRepository extends JpaRepository<SeoulBusRoute, String> {

    // 노선 번호(routeNm)에 검색어가 포함된 모든 노선 조회 (LIKE '%keyword%')
    List<SeoulBusRoute> findByRouteNmContaining(String routeNm);
}