package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;

import java.util.List;

// Search IDs, durations, fares and real-time estimates are not route identity.
record NotificationRouteIdentity(String origin, String destination, List<Leg> legs) {
    static NotificationRouteIdentity of(TransitDto.RouteOptionResponse route) {
        if (route == null || route.getSegments() == null || route.getSegments().isEmpty()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "경로의 이동 구간 정보가 필요합니다.");
        }
        return new NotificationRouteIdentity(text(route.getOriginAddress()), text(route.getDestinationAddress()),
                route.getSegments().stream().map(segment -> new Leg(
                        text(segment.getTransitType()),
                        first(segment.getTransitName(), segment.getLocalRouteId(), segment.getOdsayRouteId()),
                        first(segment.getStartStation(), segment.getOdsayStartStationId(), segment.getLocalStationId()),
                        first(segment.getEndStation(), segment.getOdsayEndStationId()),
                        segment.getStartX(), segment.getStartY(), segment.getEndX(), segment.getEndY(),
                        segment.getStations() == null ? List.of() : segment.getStations().stream()
                                .map(station -> first(station.getName(), station.getStationId())).toList()
                )).toList());
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return text(value);
        return "";
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    record Leg(String type, String line, String start, String end,
               Double startX, Double startY, Double endX, Double endY, List<String> stops) {}
}
