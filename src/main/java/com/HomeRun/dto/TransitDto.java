package com.HomeRun.dto;

import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

public class TransitDto {

    @Getter
    @Setter
    public static class RouteSearchRequest {
        private String origin;
        private String destination;
        private Double originX;
        private Double originY;
        private Double destX;
        private Double destY;
    }

    @Getter
    @Setter
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteOptionResponse {
        private String routeId;
        private Integer totalDurationMinutes;
        private Integer realTimeDurationMinutes;
        private Integer totalCost;
        private Integer transferCount;
        private List<RouteSegment> segments;
    }

    @Getter
    @Setter
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteSegment {
        private String transitType;
        private String startStation;
        private String endStation;
        private Integer durationMinutes;
        private String transitName;

        // ODsay identifiers and WGS84 coordinates
        private String odsayStartStationId;
        private String odsayRouteId;
        private Double startX;
        private Double startY;

        // Local BIS identifiers supplied by ODsay (Seoul uses these directly)
        private String localCityCode;
        private String localStationId;
        private String localRouteId;
        private String arsId;
        private Integer scheduledWaitMinutes;
        private Integer realTimeArrivalSeconds;
        private String realTimeSource;
    }
}
