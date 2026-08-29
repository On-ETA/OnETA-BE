package com.OnETA.dto.bus;

import com.OnETA.entity.SeoulBusRoute;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BusRouteSearchResponseDto {
    private String routeId;
    private String routeNm;
    private String startPoint;
    private String endPoint;
    private String term; // 배차 간격

    // Entity -> DTO 변환 메서드
    public static BusRouteSearchResponseDto from(SeoulBusRoute route) {
        return BusRouteSearchResponseDto.builder()
                .routeId(route.getRouteId())
                .routeNm(route.getRouteNm())
                .startPoint(route.getStartPoint())
                .endPoint(route.getEndPoint())
                .term(route.getTerm())
                .build();
    }
}