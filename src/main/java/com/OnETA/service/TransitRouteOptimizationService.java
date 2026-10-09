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
        LocalDateTime now = LocalDateTime.now(clock.withZone(SEOUL));
        Map<String, LocalDateTime> scheduleCache = new HashMap<>();
        FailureState failures = new FailureState();

        List<LocalDate> serviceDays = List.of(now.toLocalDate(), now.toLocalDate().plusDays(1));

        for (LocalDate serviceDate : serviceDays) {
            boolean futureOnly = scheduleType != NotificationScheduleType.FIRST_TRANSIT;
            List<Candidate> candidates = evaluateCandidates(
                    routes, scheduleType, serviceDate, now, scheduleCache, failures, futureOnly);
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
            return rank(candidates, scheduleType);
        }

        if (failures.unavailable) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
        if (failures.unsupported) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        throw new GlobalException(ErrorCode.TRANSIT_ROUTE_NOT_FOUND);
    }

    private List<TransitDto.FirstLastRouteOptionResponse> searchLast(
            List<TransitDto.RouteOptionResponse> routes, LocalDateTime now) {
        Map<String, LocalDateTime> cache = new HashMap<>();
        FailureState failures = new FailureState();
        List<Candidate> active = new ArrayList<>();
        // Retain only night-only routes whose timetable could NOT be verified.
        // A route with a known, already-ended operating interval must not reappear here.
        List<TransitDto.RouteOptionResponse> unverifiedNightRoutes = new ArrayList<>();
        for (var route : routes) {
            try {
                LocalDateTime departure = transitScheduleService.previewCurrentLastDeparture(route, now, cache);
                boolean future = departure != null && departure.isAfter(now);
                boolean currentInterval = future && isWithinCurrentLastInterval(departure, now);
                boolean searchWindow = future && isWithinSearchWindow(
                        departure, NotificationScheduleType.LAST_TRANSIT);
                if (TransitRouteClassifier.isNightOnlyRoute(route)) {
                    log.info("NIGHT_LAST_PREVIEW routeId={}, now={}, departure={}, decision={}",
                            route.getRouteId(), now, departure,
                            departure == null ? "NO_CATCHABLE_DEPARTURE"
                                    : !future ? "DEPARTURE_PASSED"
                                    : !currentInterval ? "OUTSIDE_CURRENT_INTERVAL"
                                    : !searchWindow ? "OUTSIDE_LAST_SEARCH_WINDOW" : "AVAILABLE");
                }
                if (future && currentInterval && searchWindow) {
                    active.add(new Candidate(route, departure));
                }
            } catch (GlobalException e) {
                recordFailure(failures, e);
                if (TransitRouteClassifier.isNightOnlyRoute(route)) {
                    unverifiedNightRoutes.add(route);
                    log.info("NIGHT_LAST_PREVIEW routeId={}, now={}, decision=TIME_TABLE_ERROR, code={}",
                            route.getRouteId(), now, e.getErrorCode().getCode());
                }
            } catch (RuntimeException e) {
                failures.unavailable = true;
                if (TransitRouteClassifier.isNightOnlyRoute(route)) {
                    unverifiedNightRoutes.add(route);
                    log.info("NIGHT_LAST_PREVIEW routeId={}, now={}, decision=RUNTIME_ERROR, exception={}",
                            route.getRouteId(), now, e.getClass().getSimpleName());
                }
                log.warn("Current LAST window lookup failed: routeId={}, type={}",
                        route.getRouteId(), e.getClass().getSimpleName());
            }
        }
        if (!active.isEmpty()) {
            return appendUnverifiedNightRoutes(rank(active, NotificationScheduleType.LAST_TRANSIT),
                    unverifiedNightRoutes, NotificationScheduleType.LAST_TRANSIT);
        }
        // Do not switch to tomorrow's operating day after midnight, even when the
        // current schedule provider is unavailable. An unverified direct N-bus path
        // may still be shown as informational NIGHT_ONLY, never as AVAILABLE.

        // Do not show tomorrow's 23:xx as if it were today's still-catchable last.
        // The existing frontend only prints HH:mm, so crossing service days here is unsafe.
        // After midnight (00:00-06:00), ONLY still-catchable current intervals are eligible.
        // Before midnight, an upcoming LAST from today's service day may still be shown.
        if (!now.toLocalTime().isBefore(LocalTime.of(6, 0))) {
            var upcoming = evaluateCandidates(routes, NotificationScheduleType.LAST_TRANSIT,
                    now.toLocalDate(), now, cache, failures, true);
            if (!upcoming.isEmpty()) {
                return appendUnverifiedNightRoutes(rank(upcoming, NotificationScheduleType.LAST_TRANSIT),
                        unverifiedNightRoutes, NotificationScheduleType.LAST_TRANSIT);
            }
        }
        if (!unverifiedNightRoutes.isEmpty()) {
            return appendUnverifiedNightRoutes(List.of(), unverifiedNightRoutes,
                    NotificationScheduleType.LAST_TRANSIT);
        }
        if (failures.unavailable) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
        if (failures.unsupported) throw new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        throw new GlobalException(ErrorCode.TRANSIT_ROUTE_NOT_FOUND);
    }

    /**
     * A LAST preview at 00:01 must not silently include tonight's 23:00;
     * a preview at 23:59 must not include tomorrow night's 23:00.
     */
    private boolean isWithinCurrentLastInterval(LocalDateTime departure, LocalDateTime now) {
        LocalTime localNow = now.toLocalTime();
        if (localNow.isBefore(LocalTime.of(6, 0))) {
            return !departure.isAfter(now.toLocalDate().atTime(6, 0));
        }
        if (!localNow.isBefore(LocalTime.of(21, 0))) {
            return !departure.isAfter(now.toLocalDate().plusDays(1).atTime(6, 0));
        }
        // Daytime departures are evaluated against today's service date below.
        return false;
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
                if (departure != null && isWithinSearchWindow(departure, scheduleType)
                        && (!futureOnly || departure.isAfter(now))) {
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
                        // The current FE sends route.raw unchanged as routeDetails on save.
                        // Embedding the exact selected departure in route therefore preserves
                        // the search decision without changing the FE request at all.
                        .route(candidate.route().toBuilder().selectedDepartureAt(
                                candidate.departure().atZone(SEOUL).toOffsetDateTime()).build())
                        .scheduleType(scheduleType)
                        .estimatedDepartureAt(candidate.departure().atZone(SEOUL).toOffsetDateTime())
                        .status(FirstLastRouteStatus.AVAILABLE)
                        .build())
                .toList();
    }

    /** Display an unverified night-only route without inventing a departure or allowing LAST signup. */
    private List<TransitDto.FirstLastRouteOptionResponse> appendUnverifiedNightRoutes(
            List<TransitDto.FirstLastRouteOptionResponse> available,
            List<TransitDto.RouteOptionResponse> unverifiedNightRoutes,
            NotificationScheduleType scheduleType) {
        if (unverifiedNightRoutes.isEmpty()) return available;
        List<TransitDto.FirstLastRouteOptionResponse> result = new ArrayList<>(available);
        for (var route : unverifiedNightRoutes) {
            boolean alreadyIncluded = result.stream().anyMatch(item ->
                    java.util.Objects.equals(item.getRoute().getRouteId(), route.getRouteId())
                            && java.util.Objects.equals(item.getRoute().getProvider(), route.getProvider()));
            if (alreadyIncluded) continue;
            result.add(TransitDto.FirstLastRouteOptionResponse.builder()
                    .route(route)
                    .scheduleType(scheduleType)
                    .estimatedDepartureAt(null)
                    .status(FirstLastRouteStatus.NIGHT_ONLY)
                    .build());
        }
        return result;
    }

    // Preview departures are local Seoul times; filter the trip departure, including access walking.
    // Unknown departure times cannot establish eligibility and are never returned as NIGHT_ONLY.
    private boolean isWithinSearchWindow(LocalDateTime departure, NotificationScheduleType type) {
        LocalTime time = departure.toLocalTime();
        if (type == NotificationScheduleType.LAST_TRANSIT) {
            return !time.isBefore(LocalTime.of(21, 0)) || !time.isAfter(LocalTime.of(6, 0));
        }
        return !time.isBefore(LocalTime.of(3, 0)) && !time.isAfter(LocalTime.of(9, 0));
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
