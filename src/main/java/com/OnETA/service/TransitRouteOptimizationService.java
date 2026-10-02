package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.NotificationScheduleType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
        FailureState failures = new FailureState();

        List<LocalDate> serviceDays = new ArrayList<>();
        if (scheduleType == NotificationScheduleType.LAST_TRANSIT
                && now.toLocalTime().isBefore(LocalTime.of(4, 0))) {
            serviceDays.add(now.toLocalDate().minusDays(1));
        }
        serviceDays.add(now.toLocalDate());
        serviceDays.add(now.toLocalDate().plusDays(1));

        for (LocalDate serviceDate : serviceDays) {
            List<Candidate> futureCandidates = evaluateCandidates(
                    routes, scheduleType, serviceDate, now, scheduleCache, failures);
            if (!futureCandidates.isEmpty()) {
                return rank(futureCandidates, scheduleType);
            }
        }

        if (failures.unavailable) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
        if (failures.unsupported) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        throw new GlobalException(ErrorCode.TRANSIT_ROUTE_NOT_FOUND);
    }

    private List<Candidate> evaluateCandidates(
            List<TransitDto.RouteOptionResponse> routes,
            NotificationScheduleType scheduleType,
            LocalDate serviceDate,
            LocalDateTime now,
            Map<String, LocalDateTime> scheduleCache,
            FailureState failures) {
        List<Candidate> candidates = new ArrayList<>();
        for (TransitDto.RouteOptionResponse route : routes) {
            try {
                LocalDateTime departure = transitScheduleService.previewDepartureForServiceDate(
                        route, scheduleType, serviceDate, scheduleCache);
                if (departure.isAfter(now)) {
                    candidates.add(new Candidate(route, departure));
                }
            } catch (GlobalException e) {
                if (e.getErrorCode() == ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE) {
                    failures.unavailable = true;
                } else if (e.getErrorCode() == ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED
                        || e.getErrorCode() == ErrorCode.TRANSIT_CONNECTION_UNVERIFIED) {
                    failures.unsupported = true;
                } else {
                    throw e;
                }
                log.debug("Skipping FIRST/LAST route candidate: routeId={}, provider={}, serviceDate={}, error={}",
                        route.getRouteId(), route.getProvider(), serviceDate, e.getErrorCode().getCode());
            } catch (RuntimeException e) {
                failures.unavailable = true;
                log.warn("FIRST/LAST route candidate evaluation failed: routeId={}, provider={}, serviceDate={}, type={}",
                        route.getRouteId(), route.getProvider(), serviceDate, e.getClass().getSimpleName());
            }
        }
        return candidates;
    }

    private List<TransitDto.FirstLastRouteOptionResponse> rank(
            List<Candidate> candidates, NotificationScheduleType scheduleType) {
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

    private static final class FailureState {
        private boolean unavailable;
        private boolean unsupported;
    }

    private record Candidate(TransitDto.RouteOptionResponse route, LocalDateTime departure) {}
}
