package com.OnETA.dto.bus;

import com.OnETA.entity.BusDirection;
import com.OnETA.entity.SeoulBusRoute;
import lombok.Builder;
import lombok.Getter;

// 사용자의 버스 검색 및 특정 버스 선택 후, 양 방면 표시용 DTO
@Getter
@Builder
public class BusDirectionResponseDto {
    private String routeId;
    private String routeNm;

    // 종점 방면 버튼
    private BusDirection turnaroundEnum;
    private String turnaroundName;

    // 기점 방면 버튼
    private BusDirection depotEnum;
    private String depotName;

    // 배차 간격
    private String term;

    public static BusDirectionResponseDto from(SeoulBusRoute route) {
        return BusDirectionResponseDto.builder()
                .routeId(route.getRouteId())
                .routeNm(route.getRouteNm())
                .turnaroundEnum(BusDirection.TURNAROUND)
                .turnaroundName(route.getEndPoint() + " 방면")
                .depotEnum(BusDirection.DEPOT)
                .depotName(route.getStartPoint() + " 방면")
                .term(route.getTerm()) // 배차 간격
                .build();
    }
}