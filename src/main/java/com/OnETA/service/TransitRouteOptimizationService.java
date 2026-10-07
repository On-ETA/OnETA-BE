package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.FirstLastRouteStatus;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.NotificationScheduleType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransitRouteOptimizationService {
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
                email, originX, originY, originAddress, destX, destY, destAddress, Integer.MAX_VALUE);
        if (scheduleType == NotificationScheduleType.LAST_TRANSIT) {
            return searchLast(routes, LocalDateTime.now(clock.withZone(SEOUL)));
        }
        List<TransitDto.RouteOptionResponse> nightOnlyRoutes = routes.stream()
                .filter(TransitRouteClassifier::isNightOnlyRoute)
                .toList();
        List<TransitDto.RouteOptionResponse> schedulableRoutes = routes.stream()
                .filter(route -> !TransitRouteClassifier.isNightOnlyRoute(route))
                .toList();

        LocalDateTime now = LocalDateTime.now(clock.withZone(SEOUL));
        Map<String, LocalDateTime> scheduleCache = new HashMap<>();
        FailureState failures = new FailureState();

        List<LocalDate> serviceDays = List.of(now.toLocalDate(), now.toLocalDate().plusDays(1));

        for (LocalDate serviceDate : serviceDays) {
            boolean futureOnly = scheduleType != NotificationScheduleType.FIRST_TRANSIT;
            List<Candidate> candidates = evaluateCandidates(
                    schedulableRoutes, scheduleType, serviceDate, now, scheduleCache, failures, futureOnly);
            if (candidates.isEmpty()) continue;

            if (scheduleType == NotificationScheduleType.FIRST_TRANSIT) {
                Candidate earliest = candidates.stream()
                        .min(Comparator.comparing(Candidate::departure))
                        .orElseThrow();
                // FIRST is the first connected opportunity of a service day. Once that
                // opportunity has passed, a route whose own first run begins late at night
                // must not keep today's FIRST alive; advance the whole search to the next day.
                if (!earliest.departure().isAfter(now)) continue;
            }
            return combineWithNightOnly(rank(candidates, scheduleType), nightOnlyRoutes, scheduleType);
        }

        if (!nightOnlyRoutes.isEmpty()) {
            return nightOnlyResponses(nightOnlyRoutes, scheduleType);
        }
        if (failures.unavailable) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
        if (failures.unsupported) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        throw new GlobalException(ErrorCode.TRANSIT_ROUTE_NOT_FOUND);
    }

    private List<TransitDto.FirstLastRouteOptionResponse> searchLast(
            List<TransitDto.RouteOptionResponse> routes, LocalDateTime now) {
        List<TransitDto.RouteOptionResponse> nightOnlyRoutes = routes.stream()
                .filter(TransitRouteClassifier::isNightOnlyRoute)
                .toList();
        Map<String, LocalDateTime> cache = new HashMap<>();
        FailureState failures = new FailureState();
        List<Candidate> active = new ArrayList<>();
        for (var route : routes) {
            try {
                LocalDateTime departure = transitScheduleService.previewCurrentLastDeparture(route, now, cache);
                if (departure != null && departure.isAfter(now)) active.add(new Candidate(route, departure));
            } catch (GlobalException e) {
                recordFailure(failures, e);
            } catch (RuntimeException e) {
                failures.unavailable = true;
                log.warn("Current LAST window lookup failed: routeId={}, type={}",
                        route.getRouteId(), e.getClass().getSimpleName());
            }
        }
        if (!active.isEmpty()) return appendMissingNightOnly(
                rank(active, NotificationScheduleType.LAST_TRANSIT), nightOnlyRoutes);
        // An API outage is not evidence that the current operating interval ended.
        if (failures.unavailable) {
            if (!nightOnlyRoutes.isEmpty()) {
                return nightOnlyResponses(nightOnlyRoutes, NotificationScheduleType.LAST_TRANSIT);
            }
            throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
        }

        // Only when no current interval is catchable, show the next operating cycle.
        for (var day : List.of(now.toLocalDate(), now.toLocalDate().plusDays(1))) {
            var upcoming = evaluateCandidates(routes, NotificationScheduleType.LAST_TRANSIT,
                    day, now, cache, failures, true);
            if (!upcoming.isEmpty()) return appendMissingNightOnly(
                    rank(upcoming, NotificationScheduleType.LAST_TRANSIT), nightOnlyRoutes);
        }
        if (!nightOnlyRoutes.isEmpty()) {
            return nightOnlyResponses(nightOnlyRoutes, NotificationScheduleType.LAST_TRANSIT);
        }
        if (failures.unavailable) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
        if (failures.unsupported) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        throw new GlobalException(ErrorCode.TRANSIT_ROUTE_NOT_FOUND);
    }

    private void recordFailure(FailureState failures, GlobalException e) {
        if (e.getErrorCode() == ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE) failures.unavailable = true;
        else if (e.getErrorCode() == ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED
                || e.getErrorCode() == ErrorCode.TRANSIT_CONNECTION_UNVERIFIED) failures.unsupported = true;
        else throw e;
    }

    private List<Candidate> evaluateCandidates(
            List<TransitDto.RouteOptionResponse> routes,
            NotificationScheduleType scheduleType,
            LocalDate serviceDate,
            LocalDateTime now,
            Map<String, LocalDateTime> scheduleCache,
            FailureState failures,
            boolean futureOnly) {
        List<Candidate> candidates = new ArrayList<>();
        for (TransitDto.RouteOptionResponse route : routes) {
            try {
                LocalDateTime departure = transitScheduleService.previewDepartureForServiceDate(
                        route, scheduleType, serviceDate, scheduleCache);
                if (!futureOnly || departure.isAfter(now)) {
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
                .map(candidate -> TransitDto.FirstLastRouteOptionResponse.builder()
                        .route(candidate.route())
                        .scheduleType(scheduleType)
                        .estimatedDepartureAt(candidate.departure().atZone(SEOUL).toOffsetDateTime())
                        .status(FirstLastRouteStatus.AVAILABLE)
                        .build())
                .toList();
    }

    private List<TransitDto.FirstLastRouteOptionResponse> combineWithNightOnly(
            List<TransitDto.FirstLastRouteOptionResponse> available,
            List<TransitDto.RouteOptionResponse> nightOnlyRoutes,
            NotificationScheduleType scheduleType) {
        if (nightOnlyRoutes.isEmpty()) return available;

        List<TransitDto.FirstLastRouteOptionResponse> nightResponses =
                nightOnlyResponses(nightOnlyRoutes, scheduleType);

        List<TransitDto.FirstLastRouteOptionResponse> result = new ArrayList<>();

        // NIGHT_ONLY가 존재하면 가장 짧은 심야 경로 1개를 최우선으로 포함한다.
        result.add(nightResponses.get(0));

        result.addAll(available);
        result.addAll(nightResponses.subList(1, nightResponses.size()));

        return result;
    }

    private List<TransitDto.FirstLastRouteOptionResponse> appendMissingNightOnly(
            List<TransitDto.FirstLastRouteOptionResponse> available,
            List<TransitDto.RouteOptionResponse> nightOnlyRoutes) {
        Set<String> availableIds = available.stream()
                .map(response -> response.getRoute().getRouteId())
                .collect(Collectors.toSet());
        List<TransitDto.RouteOptionResponse> missing = nightOnlyRoutes.stream()
                .filter(route -> !availableIds.contains(route.getRouteId()))
                .toList();
        if (missing.isEmpty()) return available;

        List<TransitDto.FirstLastRouteOptionResponse> result = new ArrayList<>(available);
        result.addAll(nightOnlyResponses(missing, NotificationScheduleType.LAST_TRANSIT));
        return result;
    }

    private List<TransitDto.FirstLastRouteOptionResponse> nightOnlyResponses(
            List<TransitDto.RouteOptionResponse> nightOnlyRoutes,
            NotificationScheduleType scheduleType) {
        return nightOnlyRoutes.stream()
                .sorted(Comparator.comparing(route ->
                        route.getTotalDurationMinutes() == null
                                ? Integer.MAX_VALUE : route.getTotalDurationMinutes()))
                .map(route -> TransitDto.FirstLastRouteOptionResponse.builder()
                        .route(route)
                        .scheduleType(scheduleType)
                        .estimatedDepartureAt(null)
                        .status(FirstLastRouteStatus.NIGHT_ONLY)
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
