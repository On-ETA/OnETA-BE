package com.OnETA.service;

import com.OnETA.dto.TransitDto;

import java.util.Locale;
import java.util.regex.Pattern;

public final class TransitRouteClassifier {
    private static final Pattern SEOUL_NIGHT_BUS_NAME = Pattern.compile("^N\\d{1,3}$");

    private TransitRouteClassifier() {
    }

    public static boolean isNightBusName(String transitName) {
        if (transitName == null || transitName.isBlank()) return false;
        String normalized = transitName.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return SEOUL_NIGHT_BUS_NAME.matcher(normalized).matches();
    }

    public static boolean containsNightBus(TransitDto.RouteOptionResponse route) {
        if (route == null || route.getSegments() == null) return false;
        return route.getSegments().stream().anyMatch(segment -> segment != null
                && "BUS".equals(segment.getTransitType())
                && (segment.isNightBus() || isNightBusName(segment.getTransitName())));
    }

    public static boolean isNightOnlyRoute(TransitDto.RouteOptionResponse route) {
        if (route == null || route.getSegments() == null || route.getSegments().isEmpty()) return false;

        boolean hasRide = false;
        for (TransitDto.RouteSegment segment : route.getSegments()) {
            if (segment == null) return false;
            if ("WALK".equals(segment.getTransitType())) continue;

            hasRide = true;
            if (!"BUS".equals(segment.getTransitType())
                    || (!segment.isNightBus() && !isNightBusName(segment.getTransitName()))) {
                return false;
            }
        }
        return hasRide;
    }
}
