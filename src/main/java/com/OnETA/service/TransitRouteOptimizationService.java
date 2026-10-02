package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.NotificationScheduleType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransitRouteOptimizationService {
    private static final int CANDIDATE_LIMIT = 5;
    private static final int RESULT_LIMIT = 3;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final TransitApiService transitApiService;
    private final TransitScheduleService transitScheduleService;
    private Clock clock = Clock.system(SEOUL);

    public List<TransitDto.FirstLastRouteOptionResponse> search(
            String email,
            Double originX, Double originY, String originAddress,
            Double destX, Double destY, String destAddress,
            NotificationScheduleType scheduleType) {
        validateScheduleType(scheduleType);

        List<TransitDto.RouteOptionResponse> routes = transitApiService.searchScheduleCandidates(
                email, originX, originY, originAddress, destX, destY, destAddress, CANDIDATE_LIMIT);
        LocalDateTime now = LocalDateTime.now(clock.withZone(SEOUL));
        Map<String, LocalDateTime> scheduleCache = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        boolean unavailable = false;
        boolean unsupported = false;

        for (TransitDto.RouteOptionResponse route : routes) {
            try {
                LocalDateTime departure = transitScheduleService.previewNextDeparture(
                        route, scheduleType, now, SEOUL, scheduleCache);
                candidates.add(new Candidate(route, departure));
            } catch (GlobalException e) {
                if (e.getErrorCode() == ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE) {
                    unavailable = true;
                } else if (e.getErrorCode() == ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED
                        || e.getErrorCode() == ErrorCode.TRANSIT_CONNECTION_UNVERIFIED) {
                    unsupported = true;
                } else {
                    throw e;
                }
                log.debug("Skipping FIRST/LAST route candidate: routeId={}, provider={}, error={}",
                        route.getRouteId(), route.getProvider(), e.getErrorCode().getCode());
            } catch (RuntimeException e) {
                unavailable = true;
                log.warn("FIRST/LAST route candidate evaluation failed: routeId={}, provider={}, type={}",
                        route.getRouteId(), route.getProvider(), e.getClass().getSimpleName());
            }
        }

        if (candidates.isEmpty()) {
            if (unavailable) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
            if (unsupported) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
            throw new GlobalException(ErrorCode.TRANSIT_ROUTE_NOT_FOUND);
        }

        Comparator<Candidate> order = Comparator.comparing(Candidate::departure);
        if (scheduleType == NotificationScheduleType.LAST_TRANSIT) order = order.reversed();
        order = order.thenComparing(candidate ->
                candidate.route().getTotalDurationMinutes() == null
                        ? Integer.MAX_VALUE : candidate.route().getTotalDurationMinutes());

        return candidates.stream()
                .sorted(order)
                .limit(RESULT_LIMIT)
                .map(candidate -> TransitDto.FirstLastRouteOptionResponse.builder()
                        .route(candidate.route())
                        .scheduleType(scheduleType)
                        .estimatedDepartureAt(candidate.departure().atZone(SEOUL).toOffsetDateTime())
                        .build())
                .toList();
    }

    private void validateScheduleType(NotificationScheduleType type) {
        if (type != NotificationScheduleType.FIRST_TRANSIT
                && type != NotificationScheduleType.LAST_TRANSIT) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE,
                    "FIRST_TRANSIT 또는 LAST_TRANSIT을 지정해주세요.");
        }
    }

    private record Candidate(TransitDto.RouteOptionResponse route, LocalDateTime departure) {}
}
