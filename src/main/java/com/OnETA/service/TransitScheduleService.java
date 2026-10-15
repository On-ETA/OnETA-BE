package com.OnETA.service;

import com.OnETA.dto.TransitDto;
import com.OnETA.entity.*;
import com.OnETA.repository.ScheduleSnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
@Slf4j
public class TransitScheduleService {
    private static final int FIRST_TRANSFER_BUFFER_MINUTES = 1;
    private static final int MAX_FIRST_TRANSFER_WAIT_MINUTES = 45;
    private Clock clock = Clock.systemUTC();

    private final TransitApiService transitApiService;
    private final PublicDataTransitService publicDataTransitService;
    private final ScheduleSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private SeoulBusScheduleService seoulBusScheduleService;
    private TagoSubwayScheduleService tagoSubwayScheduleService;
    private SeoulMetroTrainScheduleService seoulMetroTrainScheduleService;

    @Autowired
    void setTagoSubwayScheduleService(TagoSubwayScheduleService service) {
        this.tagoSubwayScheduleService = service;
    }

    @Autowired
    void setSeoulMetroTrainScheduleService(SeoulMetroTrainScheduleService service) {
        this.seoulMetroTrainScheduleService = service;
    }

    @Value("${odsay.api.key:}") private String apiKey;
    @Value("${odsay.schedule.url:https://api.odsay.com/v1/api}") private String scheduleBaseUrl;
    @Value("${odsay.api.referer:http://localhost:8080/}") private String odsayReferer;

    @Autowired
    public TransitScheduleService(TransitApiService transitApiService,
                                  PublicDataTransitService publicDataTransitService,
                                  ScheduleSnapshotRepository snapshotRepository,
                                  ObjectMapper objectMapper, SeoulBusScheduleService seoulBusScheduleService) {
        this(transitApiService, publicDataTransitService, snapshotRepository, objectMapper, new RestTemplate());
        this.seoulBusScheduleService = seoulBusScheduleService;
    }

    TransitScheduleService(TransitApiService transitApiService,
                           PublicDataTransitService publicDataTransitService,
                           ScheduleSnapshotRepository snapshotRepository,
                           ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.transitApiService = transitApiService;
        this.publicDataTransitService = publicDataTransitService;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /** Registration validation only: search results and first/last scheduling remain unchanged. */
    public void validateNormalOperatingTime(String details, LocalTime arrivalTime, Integer repeatDays) {
        var route = transitApiService.readSavedRoute(details);
        if (arrivalTime == null || route.getSegments() == null || route.getSegments().isEmpty()) {
            throw normalHoursUnverified(null);
        }
        long journeyMinutes = 0;
        for (var segment : route.getSegments()) {
            if (segment == null || segment.getDurationMinutes() == null
                    || segment.getDurationMinutes() < 0 || segment.getDurationMinutes() > 1440
                    || !Set.of("WALK", "BUS", "SUBWAY").contains(Objects.toString(segment.getTransitType(), ""))) {
                throw normalHoursUnverified(segment);
            }
            int wait = segment.getScheduledWaitMinutes() == null ? 0 : segment.getScheduledWaitMinutes();
            if (wait < 0 || wait > 1440) throw normalHoursUnverified(segment);
            journeyMinutes += segment.getDurationMinutes() + ("WALK".equals(segment.getTransitType()) ? 0 : wait);
        }
        if (journeyMinutes <= 0 || journeyMinutes >= 1440
                || route.getSegments().stream().noneMatch(s -> !"WALK".equals(s.getTransitType()))) {
            throw normalHoursUnverified(null);
        }
        if (repeatDays != null && (repeatDays < 0 || (repeatDays & ~127) != 0)) {
            throw normalHoursUnverified(null);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
        LocalDate firstDate = now.toLocalDate();
        if (firstDate.atTime(arrivalTime).minusMinutes(journeyMinutes).isBefore(now)) {
            firstDate = firstDate.plusDays(1);
        }
        Map<String, TransitOperatingWindow> windows = new HashMap<>();
        Map<String, LocalDateTime> scheduleCache = new HashMap<>();
        boolean oneTime = repeatDays == null || repeatDays == 0;
        for (int d = 0; d < (oneTime ? 1 : 7); d++) {
            LocalDate date = firstDate.plusDays(d);
            if (!oneTime && (repeatDays & (1 << (date.getDayOfWeek().getValue() - 1))) == 0) continue;
            LocalDateTime boarding = date.atTime(arrivalTime);
            var segments = route.getSegments();
            for (int i = segments.size() - 1; i >= 0; i--) {
                var segment = segments.get(i);
                boarding = boarding.minusMinutes(segment.getDurationMinutes());
                if (!"WALK".equals(segment.getTransitType())) {
                    validateNormalBoarding(route, segment, i, boarding, windows, scheduleCache);
                    boarding = boarding.minusMinutes(segment.getScheduledWaitMinutes() == null
                            ? 0 : segment.getScheduledWaitMinutes());
                }
            }
        }
    }

    private void validateNormalBoarding(TransitDto.RouteOptionResponse route, TransitDto.RouteSegment segment,
                                        int index, LocalDateTime boarding,
                                        Map<String, TransitOperatingWindow> windows,
                                        Map<String, LocalDateTime> scheduleCache) {
        try {
            if ("BUS".equals(segment.getTransitType())) {
                TransitOperatingWindow window = windows.get("bus|" + index);
                if (window == null) {
                    LocalDate today = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul")).toLocalDate();
                    if (SeoulBusScheduleService.usesSeoulBusSchedules(route)
                            || segment.isNightBus() || TransitRouteClassifier.isNightBusName(segment.getTransitName())) {
                        if (seoulBusScheduleService == null) throw normalHoursUnverified(segment);
                        var leg = route.toBuilder().provider("SEOUL_NIGHT").transferCount(0)
                                .totalDurationMinutes(Math.max(1, segment.getDurationMinutes()))
                                .segments(List.of(segment)).build();
                        var schedule = seoulBusScheduleService.resolve(leg, seoulBusScheduleService.today());
                        window = checkedNormalWindow(schedule.first(), schedule.last(), segment);
                    } else {
                        if (segment.getOdsayStartStationId() == null
                                || (segment.getOdsayRouteId() == null && segment.getLocalRouteId() == null)) {
                            throw normalHoursUnverified(segment);
                        }
                        window = checkedNormalWindow(
                                serviceTimeCached(segment, NotificationScheduleType.FIRST_TRANSIT, today, scheduleCache),
                                serviceTimeCached(segment, NotificationScheduleType.LAST_TRANSIT, today, scheduleCache), segment);
                    }
                    windows.put("bus|" + index, window);
                }
                // Bus APIs expose recurring stop times, not historical service-date timetables.
                LocalTime first = window.first().toLocalTime();
                LocalTime last = window.last().toLocalTime();
                LocalTime at = boarding.toLocalTime();
                boolean within = last.isBefore(first)
                        ? !at.isBefore(first) || !at.isAfter(last)
                        : !at.isBefore(first) && !at.isAfter(last);
                if (!within) throw normalOutsideHours(segment);
                return;
            }
            // A post-midnight train may still belong to the preceding service day's timetable.
            LocalDate day = boarding.toLocalDate();
            TransitOperatingWindow current = normalSubwayWindow(route, segment, index, day, windows, scheduleCache);
            if (!boarding.isBefore(current.first()) && !boarding.isAfter(current.last())) return;
            if (boarding.isBefore(current.first())) {
                TransitOperatingWindow previous = normalSubwayWindow(route, segment, index,
                        day.minusDays(1), windows, scheduleCache);
                if (!boarding.isBefore(previous.first()) && !boarding.isAfter(previous.last())) return;
            }
            throw normalOutsideHours(segment);
        } catch (com.OnETA.common.exception.GlobalException e) {
            if (e.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_NIGHT_BUS_OUTSIDE_HOURS
                    || e.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS
                    || e.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_NIGHT_BUS_HOURS_UNVERIFIED
                    || e.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_NORMAL_HOURS_UNVERIFIED) throw e;
            throw normalHoursUnverified(segment);
        } catch (RuntimeException e) {
            log.warn("Normal route timetable verification failed: line={}, exception={}",
                    segment.getTransitName(), e.getClass().getSimpleName());
            throw normalHoursUnverified(segment);
        }
    }

    private TransitOperatingWindow normalSubwayWindow(TransitDto.RouteOptionResponse route,
            TransitDto.RouteSegment segment, int index, LocalDate day,
            Map<String, TransitOperatingWindow> windows, Map<String, LocalDateTime> scheduleCache) {
        String key = "subway|" + index + "|" + day;
        if (windows.containsKey(key)) return windows.get(key);
        TransitOperatingWindow window;
        if (!SeoulBusScheduleService.usesSeoulBusSchedules(route)
                && segment.getOdsayStartStationId() != null && segment.getOdsayEndStationId() != null) {
            try {
                window = checkedNormalWindow(
                        serviceTimeCached(segment, NotificationScheduleType.FIRST_TRANSIT, day, scheduleCache),
                        serviceTimeCached(segment, NotificationScheduleType.LAST_TRANSIT, day, scheduleCache), segment);
                windows.put(key, window);
                return window;
            } catch (RuntimeException e) {
                // A provider failure is not proof the line is closed. Resolve
                // public station timetables before rejecting registration.
                log.info("Normal subway timetable fallback: provider={}, line={}, start={}, end={}, day={}, failureType={}",
                        route.getProvider(), segment.getTransitName(), segment.getStartStation(),
                        segment.getEndStation(), day, e.getClass().getSimpleName());
            }
        }
        var schedule = resolveKakaoSubwaySchedule(segment, day);
        window = checkedNormalWindow(schedule.first(), schedule.last(), segment);
        windows.put(key, window);
        return window;
    }

    private TransitOperatingWindow checkedNormalWindow(LocalDateTime first, LocalDateTime last,
                                                        TransitDto.RouteSegment segment) {
        if (first == null || last == null) throw normalHoursUnverified(segment);
        var window = new TransitOperatingWindow(first, last);
        long minutes = Duration.between(window.first(), window.last()).toMinutes();
        if (minutes <= 0 || minutes >= 1440) throw normalHoursUnverified(segment);
        return window;
    }

    private com.OnETA.common.exception.GlobalException normalOutsideHours(TransitDto.RouteSegment segment) {
        boolean night = segment.isNightBus() || TransitRouteClassifier.isNightBusName(segment.getTransitName());
        var code = night ? com.OnETA.common.error.ErrorCode.TRANSIT_NIGHT_BUS_OUTSIDE_HOURS
                : com.OnETA.common.error.ErrorCode.TRANSIT_NORMAL_OUTSIDE_HOURS;
        String line = segment.getTransitName() == null || segment.getTransitName().isBlank()
                ? "해당 교통수단" : segment.getTransitName();
        return new com.OnETA.common.exception.GlobalException(code,
                "선택한 도착 시간은 " + line + " 운행 시간에 맞지 않습니다. 도착 시간을 변경해주세요.");
    }

    private com.OnETA.common.exception.GlobalException normalHoursUnverified(TransitDto.RouteSegment segment) {
        boolean night = segment != null && (segment.isNightBus()
                || TransitRouteClassifier.isNightBusName(segment.getTransitName()));
        return new com.OnETA.common.exception.GlobalException(night
                ? com.OnETA.common.error.ErrorCode.TRANSIT_NIGHT_BUS_HOURS_UNVERIFIED
                : com.OnETA.common.error.ErrorCode.TRANSIT_NORMAL_HOURS_UNVERIFIED);
    }

    @Transactional
    public Decision evaluate(ArrivalNotification notification, LocalDate date, LocalDateTime now, ZoneId zone) {
        NotificationScheduleType type = notification.getScheduleType();
        String details = notification.getRouteDetails();
        String hash = hash(details, type);
        Optional<ScheduleSnapshot> existing = snapshotRepository
                .findForUpdate(notification.getId(), date, type, hash);
        if (existing.isEmpty() && type == NotificationScheduleType.LAST_TRANSIT && date.isBefore(now.toLocalDate())) {
            // Preserve valid pre-upgrade snapshots from an interval crossing midnight.
            existing = snapshotRepository.findForUpdate(notification.getId(), date, type, hash(details));
        }
        // Previous-day Seoul schedules may only use a snapshot already fetched that day.
        if (existing.isEmpty() && date.isBefore(now.toLocalDate())) return null;
        ScheduleSnapshot snapshot = existing.orElseGet(() -> createSnapshot(notification, date, type, hash, zone, now));
        if (snapshot.getEvaluationMode() == ScheduleEvaluationMode.FINISHED) return null;
        refreshSelectedPreviewSource(snapshot, notification, now);

        int offset = notification.getReminderOffsetMinutesList().stream()
                .max(Integer::compareTo).orElse(notification.getReminderOffsetMinutes());
        LocalDateTime scheduled = snapshot.getEffectiveScheduledAt();
        LocalDateTime deadline = snapshot.getEffectiveDepartureAt();
        DeliveryPhase phase = DeliveryPhase.BASE;

        if ("SEOUL_BUS_TRANSFER".equals(snapshot.getSource())) {
            return evaluateSeoulTransfer(snapshot, notification, now, type, offset);
        }

        if ("SEOUL_BUS".equals(snapshot.getSource())) {
            if (!deadline.isAfter(now)) return null;
            return new Decision(scheduled, deadline, phase, snapshot.getBaseDepartureAt(),
                    snapshot.getEffectiveDepartureAt(), false, snapshot.getEstimatedDurationMinutes());
        }

        if (type == NotificationScheduleType.FIRST_TRANSIT) {
            if (now.isAfter(snapshot.getFirstOpportunityDeadline())) {
                if (snapshot.getRecoveryStatus() == RecoveryStatus.DELIVERY_CREATED
                        || snapshot.getRecoveryStatus() == RecoveryStatus.NO_CANDIDATE
                        || snapshot.getRecoveryStatus() == RecoveryStatus.FAILED) return null;
                if (snapshot.getRecoveryNextRetryAt() != null && now.isBefore(snapshot.getRecoveryNextRetryAt())) return null;
                if (snapshot.getRecoveryEvaluationDeadline() != null
                        && !now.isBefore(snapshot.getRecoveryEvaluationDeadline())) {
                    snapshot.markRecovery(RecoveryStatus.FAILED);
                    snapshotRepository.save(snapshot);
                    return null;
                }
                RecoveryCandidate candidate;
                try {
                    candidate = findRecoveryCandidate(snapshot, notification, now, zone);
                } catch (RuntimeException e) {
                    snapshot.markRecoveryRetry(now.plusMinutes(1));
                    snapshotRepository.save(snapshot);
                    return null;
                }
                if (candidate == null) {
                    snapshot.markRecovery(RecoveryStatus.NO_CANDIDATE);
                    snapshotRepository.save(snapshot);
                    return null;
                }
                scheduled = candidate.scheduledAt();
                deadline = candidate.boardingAt();
                phase = DeliveryPhase.RECOVERY;
            } else {
                if (snapshot.getRealtimeEvaluationStartAt() == null
                        || !now.isBefore(snapshot.getRealtimeEvaluationStartAt())) {
                    evaluateFirstSafety(snapshot, notification, now, zone);
                }
                scheduled = snapshot.getEffectiveScheduledAt();
                deadline = snapshot.getEffectiveDepartureAt();
            }
        }
        if (type == NotificationScheduleType.LAST_TRANSIT) {
            scheduled = snapshot.getEffectiveScheduledAt();
            deadline = snapshot.getEffectiveDepartureAt();
        }
        int duration = snapshot.getEstimatedDurationMinutes();
        return new Decision(scheduled, deadline, phase, snapshot.getBaseDepartureAt(),
                snapshot.getEffectiveDepartureAt(), phase == DeliveryPhase.RECOVERY, duration);
    }

    @Transactional
    public void markRecoveryDeliveryCreated(ArrivalNotification notification, LocalDate date) {
        String hash = hash(notification.getRouteDetails(), notification.getScheduleType());
        snapshotRepository.findForUpdate(notification.getId(), date, notification.getScheduleType(), hash)
                .ifPresent(snapshot -> { snapshot.markRecoveryDeliveryCreated(); snapshotRepository.save(snapshot); });
    }

    @Transactional(readOnly = true)
    public boolean shouldAdvanceToNextServiceDay(ArrivalNotification notification, LocalDate serviceDate,
                                                 LocalDateTime now) {
        if (notification.getRepeatDays() != null && notification.getRepeatDays() != 0) return false;

        String routeHash = hash(notification.getRouteDetails(), notification.getScheduleType());
        var snapshot = snapshotRepository
                .findByNotificationIdAndServiceDateAndScheduleTypeAndRouteHash(
                        notification.getId(), serviceDate, notification.getScheduleType(), routeHash)
                .orElse(null);
        if (snapshot == null || snapshot.getEffectiveDepartureAt().isAfter(now)) return false;

        if (notification.getScheduleType() == NotificationScheduleType.LAST_TRANSIT) {
            return true;
        }
        if (notification.getScheduleType() != NotificationScheduleType.FIRST_TRANSIT) {
            return false;
        }

        if ("SEOUL_BUS".equals(snapshot.getSource())) {
            return true;
        }
        if ("SEOUL_BUS_TRANSFER".equals(snapshot.getSource())) {
            return !now.isBefore(snapshot.getBaseDepartureAt().plusMinutes(60));
        }

        return snapshot.getRecoveryStatus() == RecoveryStatus.NO_CANDIDATE
                || snapshot.getRecoveryStatus() == RecoveryStatus.FAILED
                || snapshot.getRecoveryStatus() == RecoveryStatus.FINISHED;
    }

    private ScheduleSnapshot createSnapshot(ArrivalNotification n, LocalDate date,
                                             NotificationScheduleType type, String hash, ZoneId zone, LocalDateTime now) {
        return snapshotRepository.save(buildSnapshot(n, date, type, hash, zone, now));
    }

    private ScheduleSnapshot buildSnapshot(ArrivalNotification n, LocalDate date,
                                             NotificationScheduleType type, String hash, ZoneId zone, LocalDateTime now) {
        TransitDto.RouteOptionResponse route = transitApiService.readSavedRoute(n.getRouteDetails());
        Map<String, LocalDateTime> cache = new HashMap<>();
        RouteSchedulePlan plan = type == NotificationScheduleType.LAST_TRANSIT && date.equals(now.toLocalDate())
                ? calculateCurrentLastPlan(route, now, cache) : null;
        if (plan == null) plan = calculateRoutePlan(route, type, date, cache);
        int offset = n.getReminderOffsetMinutesList().stream()
                .max(Integer::compareTo).orElse(n.getReminderOffsetMinutes());
        LocalDateTime scheduled = plan.departure().minusMinutes(offset);
        LocalDateTime evaluationStart = scheduled.minusMinutes(plan.evaluationLeadMinutes());
        ScheduleSnapshot snapshot = new ScheduleSnapshot(n, date, type, hash, plan.departure(), scheduled,
                evaluationStart, LocalDateTime.now(ZoneOffset.UTC), plan.durationMinutes());
        if ("SEOUL_BUS".equals(plan.source())) {
            snapshot.useSeoulBusSource();
        } else if ("SEOUL_BUS_TRANSFER".equals(plan.source())) {
            snapshot.useSeoulTransferSource(plan.providerDetails());
        }
        return snapshot;
    }

    private RouteSchedulePlan calculateRoutePlan(TransitDto.RouteOptionResponse route,
                                                  NotificationScheduleType type,
                                                  LocalDate date,
                                                  Map<String, LocalDateTime> scheduleCache) {
        if (route == null || route.getSegments() == null || route.getSegments().isEmpty()) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        }

        long rideCount = route.getSegments().stream()
                .filter(segment -> !"WALK".equals(segment.getTransitType()))
                .count();
        if (type == NotificationScheduleType.FIRST_TRANSIT && rideCount > 1) {
            return calculateConnectedFirstPlan(route, date, scheduleCache);
        }

        if (type == NotificationScheduleType.LAST_TRANSIT
                && SeoulBusScheduleService.isKakao(route) && pureSubwayTransfer(route)) {
            try {
                return calculateVerifiedKakaoSubwayLastPlan(route, date);
            } catch (com.OnETA.common.exception.GlobalException e) {
                if (e.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_CONNECTION_UNVERIFIED) {
                    throw e; // Verified train trips cannot form the requested transfer.
                }
                log.info("Verified Kakao LAST trips unavailable; using prior conservative fallback: routeId={}, date={}, code={}",
                        route.getRouteId(), date, e.getErrorCode().getCode());
            }
        }

        // The LAST of each line is not necessarily the latest usable connected train.
        // Prefer ODsay's whole-journey LAST itinerary when its exact legs match.
        if (type == NotificationScheduleType.LAST_TRANSIT
                && OdsayConnectedLastSubway.supports(route)) {
            var connected = resolveConnectedLastSubway(route, date);
            if (connected.isPresent()) return connectedLastPlan(route, connected.get());
        }

        if (SeoulBusScheduleService.usesSeoulBusSchedules(route)) {
            long rides = route.getSegments().stream().filter(s -> !"WALK".equals(s.getTransitType())).count();
            if (rides > 1 || route.getSegments().stream().anyMatch(s -> "SUBWAY".equals(s.getTransitType()))) {
                boolean hasSubway = route.getSegments().stream()
                        .anyMatch(s -> "SUBWAY".equals(s.getTransitType()));
                var times = hasSubway
                        ? resolveKakaoRouteSchedules(route, date,
                                type == NotificationScheduleType.LAST_TRANSIT && pureSubwayTransfer(route))
                        : seoulBusScheduleService.resolveRoute(route, date);
                List<LocalDateTime> bounds = new ArrayList<>();
                int prefix = 0, ride = 0;
                for (var segment : route.getSegments()) {
                    if (!"WALK".equals(segment.getTransitType())) {
                        var time = times.get(ride++);
                        bounds.add(type == NotificationScheduleType.FIRST_TRANSIT ? time.first() : time.last());
                    }
                    prefix += Math.max(0, segment.getDurationMinutes() == null ? 0 : segment.getDurationMinutes());
                }
                var plan = TransitScheduleCalculator.conservative(route.getSegments(), bounds, type);
                return new RouteSchedulePlan(plan.departure(), plan.durationMinutes(),
                        "SEOUL_BUS_TRANSFER", objectMapper.writeValueAsString(times), Math.max(60, prefix));
            }

            var times = resolveKakaoBusSchedule(route, date);
            LocalDateTime boarding = type == NotificationScheduleType.FIRST_TRANSIT ? times.first() : times.last();
            var plan = TransitScheduleCalculator.calculate(route.getSegments(), List.of(boarding), type);
            return new RouteSchedulePlan(plan.departure(), plan.durationMinutes(), "SEOUL_BUS", null, 0);
        }

        List<TransitDto.RouteSegment> segments = route.getSegments();
        List<LocalDateTime> candidates = new ArrayList<>();
        for (TransitDto.RouteSegment segment : segments) {
            if ("BUS".equals(segment.getTransitType()) || "SUBWAY".equals(segment.getTransitType())) {
                LocalDateTime service = serviceTimeCached(segment, type, date, scheduleCache);
                if (service == null) {
                    throw new com.OnETA.common.exception.GlobalException(
                            com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
                }
                candidates.add(service);
            }
        }
        if (candidates.isEmpty()) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        }

        TransitScheduleCalculator.Plan plan;
        try {
            plan = TransitScheduleCalculator.calculate(segments, candidates, type);
        } catch (com.OnETA.common.exception.GlobalException ex) {
            if (ex.getErrorCode() != com.OnETA.common.error.ErrorCode.TRANSIT_CONNECTION_UNVERIFIED) throw ex;
            plan = TransitScheduleCalculator.conservative(segments, candidates, type);
        }
        int lead = Math.max(15, Math.min(60, plan.durationMinutes()));
        return new RouteSchedulePlan(plan.departure(), plan.durationMinutes(), "ODSAY", null, lead);
    }

    /**
     * Backward-match actual same-train station schedules instead of assuming a
     * five-minute wait plus ten minutes for every subway interchange.
     * Each upstream trip must reach the next boarding with walk time and a
     * verified 1..45 minute waiting window.
     */
    private RouteSchedulePlan calculateVerifiedKakaoSubwayLastPlan(
            TransitDto.RouteOptionResponse route, LocalDate serviceDate) {
        if (seoulMetroTrainScheduleService == null || !pureSubwayTransfer(route)) {
            throw connectionUnverified();
        }
        List<TransitDto.RouteSegment> segments = route.getSegments();
        List<Integer> rideIndices = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            var segment = segments.get(i);
            if (segment == null || segment.getDurationMinutes() == null
                    || segment.getDurationMinutes() < 0) throw connectionUnverified();
            if ("SUBWAY".equals(segment.getTransitType())) rideIndices.add(i);
            else if (!"WALK".equals(segment.getTransitType())) throw connectionUnverified();
        }

        int lastIndex = rideIndices.get(rideIndices.size() - 1);
        List<ConnectedLastTrip> suffixes = new ArrayList<>();
        for (var trip : seoulMetroTrainScheduleService.tripsForServiceDate(
                segments.get(lastIndex), serviceDate)) {
            suffixes.add(new ConnectedLastTrip(trip.departure(), trip.arrival()));
        }
        if (suffixes.isEmpty()) throw connectionUnverified();

        for (int ride = rideIndices.size() - 2; ride >= 0; ride--) {
            int index = rideIndices.get(ride);
            int nextIndex = rideIndices.get(ride + 1);
            int walk = 0;
            for (int i = index + 1; i < nextIndex; i++) {
                walk = Math.addExact(walk, segments.get(i).getDurationMinutes());
            }
            List<ConnectedLastTrip> connected = new ArrayList<>();
            for (var trip : seoulMetroTrainScheduleService.tripsForServiceDate(
                    segments.get(index), serviceDate)) {
                LocalDateTime ready = trip.arrival().plusMinutes(walk);
                for (ConnectedLastTrip suffix : suffixes) {
                    long waiting = Duration.between(ready, suffix.departure()).toMinutes();
                    if (waiting >= FIRST_TRANSFER_BUFFER_MINUTES
                            && waiting <= MAX_FIRST_TRANSFER_WAIT_MINUTES) {
                        connected.add(new ConnectedLastTrip(trip.departure(), suffix.finalArrival()));
                        break;
                    }
                }
            }
            if (connected.isEmpty()) throw connectionUnverified();
            suffixes = connected;
        }

        ConnectedLastTrip latest = suffixes.stream()
                .max(Comparator.comparing(ConnectedLastTrip::departure))
                .orElseThrow(this::connectionUnverified);
        int access = 5; // Keep the existing Kakao access safety margin.
        for (int i = 0; i < rideIndices.get(0); i++) {
            access = Math.addExact(access, segments.get(i).getDurationMinutes());
        }
        int egress = 0;
        for (int i = lastIndex + 1; i < segments.size(); i++) {
            egress = Math.addExact(egress, segments.get(i).getDurationMinutes());
        }
        LocalDateTime departure = latest.departure().minusMinutes(access);
        LocalDateTime arrival = latest.finalArrival().plusMinutes(egress);
        long seconds = Duration.between(departure, arrival).getSeconds();
        if (seconds <= 0 || seconds > 1440 * 60L) throw connectionUnverified();
        int duration = Math.toIntExact((seconds + 59) / 60);
        var bindings = resolveKakaoRouteSchedules(route, serviceDate, true);
        log.info("Verified Kakao connected LAST: routeId={}, serviceDate={}, departure={}, rides={}",
                route.getRouteId(), serviceDate, departure, rideIndices.size());
        return new RouteSchedulePlan(departure, duration, "SEOUL_BUS_TRANSFER",
                objectMapper.writeValueAsString(bindings), Math.max(60, duration));
    }

    private record ConnectedLastTrip(LocalDateTime departure, LocalDateTime finalArrival) {}

    private RouteSchedulePlan calculateConnectedFirstPlan(
            TransitDto.RouteOptionResponse route,
            LocalDate serviceDate,
            Map<String, LocalDateTime> scheduleCache) {
        boolean kakao = SeoulBusScheduleService.isKakao(route);
        List<SeoulBusScheduleService.Schedule> providerSchedules = null;
        if (kakao) {
            boolean hasSubway = route.getSegments().stream()
                    .anyMatch(segment -> "SUBWAY".equals(segment.getTransitType()));
            providerSchedules = hasSubway
                    ? resolveKakaoRouteSchedules(route, serviceDate)
                    : seoulBusScheduleService.resolveRoute(route, serviceDate);
        }

        int accessWalk = 0;
        int rideIndex = 0;
        LocalDateTime departure = null;
        LocalDateTime ready = null;

        for (TransitDto.RouteSegment segment : route.getSegments()) {
            if (segment == null || segment.getDurationMinutes() == null
                    || segment.getDurationMinutes() < 0) {
                throw new com.OnETA.common.exception.GlobalException(
                        com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
            }

            if ("WALK".equals(segment.getTransitType())) {
                if (departure == null) accessWalk = Math.addExact(accessWalk, segment.getDurationMinutes());
                else ready = ready.plusMinutes(segment.getDurationMinutes());
                continue;
            }
            if (!"BUS".equals(segment.getTransitType()) && !"SUBWAY".equals(segment.getTransitType())) {
                throw new com.OnETA.common.exception.GlobalException(
                        com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
            }

            FirstBoundary boundary;
            if (kakao) {
                if (providerSchedules == null || rideIndex >= providerSchedules.size()) {
                    throw new com.OnETA.common.exception.GlobalException(
                            com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
                }
                SeoulBusScheduleService.Schedule schedule = providerSchedules.get(rideIndex);
                boundary = new FirstBoundary(schedule.first(), schedule);
            } else {
                boundary = resolveFirstBoundary(route, segment, serviceDate, scheduleCache);
            }
            rideIndex++;

            LocalDateTime boarding;
            LocalDateTime alighting;
            if (departure == null) {
                boarding = boundary.firstDeparture();
                int accessSafetyMinutes = kakao ? 5 : 0;
                departure = boarding.minusMinutes(accessWalk + accessSafetyMinutes);
                alighting = boarding.plusMinutes(segment.getDurationMinutes());
            } else {
                LocalDateTime earliestBoarding = ready.plusMinutes(FIRST_TRANSFER_BUFFER_MINUTES);
                if (!boundary.firstDeparture().isBefore(earliestBoarding)) {
                    boarding = boundary.firstDeparture();
                    alighting = boarding.plusMinutes(segment.getDurationMinutes());
                } else if ("SUBWAY".equals(segment.getTransitType())) {
                    SeoulMetroTrainScheduleService.TripWindow trip;
                    try {
                        trip = seoulMetroTrainScheduleService
                                .firstTripAtOrAfter(segment, serviceDate, earliestBoarding)
                                .orElseThrow(this::connectionUnverified);
                    } catch (com.OnETA.common.exception.GlobalException ex) {
                        if (ex.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE) {
                            throw ex;
                        }
                        throw connectionUnverified();
                    }
                    boarding = trip.departure();
                    alighting = trip.arrival();
                } else {
                    throw connectionUnverified();
                }

                long waitMinutes = Duration.between(ready, boarding).toMinutes();
                if (waitMinutes < FIRST_TRANSFER_BUFFER_MINUTES
                        || waitMinutes > MAX_FIRST_TRANSFER_WAIT_MINUTES) {
                    throw connectionUnverified();
                }
            }
            ready = alighting;
        }

        if (departure == null || ready == null || !ready.isAfter(departure)) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        }

        long seconds = Duration.between(departure, ready).getSeconds();
        int duration = Math.toIntExact((seconds + 59) / 60);
        if (kakao) {
            String details = objectMapper.writeValueAsString(providerSchedules);
            return new RouteSchedulePlan(departure, duration,
                    "SEOUL_BUS_TRANSFER", details, Math.max(60, duration));
        }
        int lead = Math.max(15, Math.min(60, duration));
        return new RouteSchedulePlan(departure, duration, "ODSAY", null, lead);
    }

    private FirstBoundary resolveFirstBoundary(
            TransitDto.RouteOptionResponse route,
            TransitDto.RouteSegment segment,
            LocalDate serviceDate,
            Map<String, LocalDateTime> scheduleCache) {
        LocalDateTime first = serviceTimeCached(
                segment, NotificationScheduleType.FIRST_TRANSIT, serviceDate, scheduleCache);
        if (first == null) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        }
        return new FirstBoundary(first, null);
    }

    private com.OnETA.common.exception.GlobalException connectionUnverified() {
        return new com.OnETA.common.exception.GlobalException(
                com.OnETA.common.error.ErrorCode.TRANSIT_CONNECTION_UNVERIFIED);
    }

    private static boolean pureSubwayTransfer(TransitDto.RouteOptionResponse route) {
        if (route == null || route.getSegments() == null) return false;
        var rides = route.getSegments().stream()
                .filter(segment -> segment != null && !"WALK".equals(segment.getTransitType())).toList();
        return rides.size() >= 2
                && rides.stream().allMatch(segment -> "SUBWAY".equals(segment.getTransitType()));
    }

    private List<SeoulBusScheduleService.Schedule> resolveKakaoRouteSchedules(
            TransitDto.RouteOptionResponse route, LocalDate serviceDate) {
        return resolveKakaoRouteSchedules(route, serviceDate, false);
    }

    private List<SeoulBusScheduleService.Schedule> resolveKakaoRouteSchedules(
            TransitDto.RouteOptionResponse route, LocalDate serviceDate, boolean verifyLastTrain) {
        List<SeoulBusScheduleService.Schedule> schedules = new ArrayList<>();
        for (TransitDto.RouteSegment segment : route.getSegments()) {
            if ("WALK".equals(segment.getTransitType())) continue;
            if ("SUBWAY".equals(segment.getTransitType())) {
                schedules.add(verifyLastTrain
                        ? resolveKakaoSubwayScheduleForLast(segment, serviceDate)
                        : resolveKakaoSubwaySchedule(segment, serviceDate));
                continue;
            }
            if ("BUS".equals(segment.getTransitType())) {
                schedules.add(resolveKakaoBusSchedule(
                        route.toBuilder().transferCount(0).segments(List.of(segment)).build(), serviceDate));
                continue;
            }
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        }
        return schedules;
    }

    /**
     * TAGO has no train IDs; both U/D can look reachable by coincidental
     * station times. For LAST subway-to-subway transfers, prefer the Seoul Metro
     * provider, which matches the same train number at both stations.
     * Keep the conservative TAGO fallback if the verified provider is unavailable.
     */
    private SeoulBusScheduleService.Schedule resolveKakaoSubwayScheduleForLast(
            TransitDto.RouteSegment segment, LocalDate serviceDate) {
        if (seoulMetroTrainScheduleService != null) {
            try {
                var verified = seoulMetroTrainScheduleService.resolve(segment, serviceDate);
                log.info("Kakao LAST verified subway leg: line={}, from={}, to={}, date={}, last={}",
                        segment.getTransitName(), segment.getStartStation(), segment.getEndStation(),
                        serviceDate, verified.last());
                return verified;
            } catch (RuntimeException e) {
                log.info("Kakao LAST verified subway timetable unavailable: line={}, from={}, to={}, "
                                + "date={}, reason={}; using conservative TAGO fallback",
                        segment.getTransitName(), segment.getStartStation(), segment.getEndStation(),
                        serviceDate, e.getClass().getSimpleName());
            }
        }
        return resolveKakaoSubwaySchedule(segment, serviceDate);
    }

    private SeoulBusScheduleService.Schedule resolveKakaoSubwaySchedule(
            TransitDto.RouteSegment segment, LocalDate serviceDate) {
        com.OnETA.common.exception.GlobalException tagoFailure = null;
        try {
            return tagoSubwayScheduleService.resolve(segment, serviceDate);
        } catch (com.OnETA.common.exception.GlobalException ex) {
            if (ex.getErrorCode() != com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED
                    && ex.getErrorCode() != com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE) {
                throw ex;
            }
            tagoFailure = ex;
            log.info("TAGO subway schedule unavailable; trying Seoul Metro fallback: line={}, start={}, end={}, serviceDate={}, code={}",
                    segment.getTransitName(), segment.getStartStation(), segment.getEndStation(),
                    serviceDate, ex.getErrorCode().getCode());
        }

        try {
            var resolved = seoulMetroTrainScheduleService.resolve(segment, serviceDate);
            log.info("Seoul Metro subway fallback resolved: line={}, start={}, end={}, serviceDate={}",
                    segment.getTransitName(), segment.getStartStation(), segment.getEndStation(), serviceDate);
            return resolved;
        } catch (com.OnETA.common.exception.GlobalException ex) {
            if (tagoFailure != null) ex.addSuppressed(tagoFailure);
            throw ex;
        } catch (RuntimeException ex) {
            var unavailable = new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
            if (tagoFailure != null) unavailable.addSuppressed(tagoFailure);
            unavailable.addSuppressed(ex);
            throw unavailable;
        }
    }

    /**
     * Seoul-only stop timetable lookups cannot cover every metropolitan bus.
     * Resolve the exact Kakao boarding stop and downstream stop through ODsay,
     * which publishes first/last times by stop and direction.
     */
    private SeoulBusScheduleService.Schedule resolveKakaoBusSchedule(
            TransitDto.RouteOptionResponse route, LocalDate serviceDate) {
        try {
            return seoulBusScheduleService.resolve(route, serviceDate);
        } catch (com.OnETA.common.exception.GlobalException seoulFailure) {
            var rides = route.getSegments().stream()
                    .filter(segment -> segment != null && "BUS".equals(segment.getTransitType()))
                    .toList();
            if (!SeoulBusScheduleService.isKakao(route) || rides.size() != 1
                    || rides.get(0).getBusType() != com.OnETA.dto.BusType.METROPOLITAN) {
                throw seoulFailure;
            }
            try {
                return resolveOdsayMetropolitanStopSchedule(rides.get(0), serviceDate);
            } catch (RuntimeException odsayFailure) {
                log.info("Kakao metropolitan timetable fallback rejected: routeId={}, line={}, date={}, errorType={}",
                        route.getRouteId(), rides.get(0).getTransitName(), serviceDate,
                        odsayFailure.getClass().getSimpleName());
                if (odsayFailure instanceof com.OnETA.common.exception.GlobalException global) {
                    global.addSuppressed(seoulFailure);
                    throw global;
                }
                seoulFailure.addSuppressed(odsayFailure);
                throw seoulFailure;
            }
        }
    }

    private SeoulBusScheduleService.Schedule resolveOdsayMetropolitanStopSchedule(
            TransitDto.RouteSegment segment, LocalDate serviceDate) {
        if (!isApiKeyConfigured() || segment.getStartX() == null || segment.getStartY() == null
                || segment.getEndX() == null || segment.getEndY() == null
                || segment.getTransitName() == null || segment.getTransitName().isBlank()) {
            throw connectionUnverified();
        }

        List<JsonNode> starts = findOdsayBusStops(
                segment.getStartX(), segment.getStartY(), segment.getStartStation());
        List<JsonNode> ends = findOdsayBusStops(
                segment.getEndX(), segment.getEndY(), segment.getEndStation());
        List<SeoulBusScheduleService.Schedule> matched = new ArrayList<>();
        // A route at the boarding stop must also appear later along that SAME
        // route ID at the alighting stop. An identical bus number is not enough.
        for (JsonNode start : starts) {
            String startId = start.path("stationID").asText("");
            JsonNode startDetails = request("/busStationInfo", Map.of("stationID", startId));
            ensureNoOdsayError(startDetails);
            for (JsonNode startLane : odsayRows(startDetails.path("result").path("lane"))) {
                if (!sameBusName(startLane.path("busNo").asText(""), segment.getTransitName())) continue;
                String busId = startLane.path("busID").asText("");
                int startOrder = startLane.path("busStationIdx").asInt(-1);
                if (busId.isBlank() || startOrder < 0) continue;

                for (JsonNode end : ends) {
                    String endId = end.path("stationID").asText("");
                    JsonNode endDetails = request("/busStationInfo", Map.of("stationID", endId));
                    ensureNoOdsayError(endDetails);
                    for (JsonNode endLane : odsayRows(endDetails.path("result").path("lane"))) {
                        if (!busId.equals(endLane.path("busID").asText(""))
                                || !sameBusName(endLane.path("busNo").asText(""), segment.getTransitName())
                                || endLane.path("busStationIdx").asInt(-1) <= startOrder) continue;
                        LocalDateTime first = parseTime(startLane.path("busFirstTime").asText(null), serviceDate);
                        LocalDateTime last = parseTime(startLane.path("busLastTime").asText(null), serviceDate);
                        if (first == null || last == null) continue;
                        if (last.isBefore(first)) last = last.plusDays(1);
                        if (!last.isAfter(first) || Duration.between(first, last).toHours() >= 24) continue;
                        matched.add(new SeoulBusScheduleService.Schedule(
                                startId, "", "ODSAY_METRO:" + busId, first, last, startOrder,
                                endId, endLane.path("busStationIdx").asInt(-1)));
                    }
                }
            }
        }

        var unique = matched.stream().distinct().toList();
        log.info("Kakao metropolitan stop timetable: line={}, start={}, end={}, startStops={}, endStops={}, matches={}",
                segment.getTransitName(), segment.getStartStation(), segment.getEndStation(),
                starts.size(), ends.size(), unique.size());
        if (unique.size() != 1) throw connectionUnverified();
        return unique.get(0);
    }

    private List<JsonNode> findOdsayBusStops(Double x, Double y, String name) {
        if (name == null || name.isBlank() || !Double.isFinite(x) || !Double.isFinite(y)) {
            throw connectionUnverified();
        }
        JsonNode root = request("/pointSearch", Map.of(
                "x", Double.toString(x), "y", Double.toString(y),
                "radius", "120", "stationClass", "1"));
        ensureNoOdsayError(root);
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode stop : odsayRows(root.path("result").path("station"))) {
            if (stop.path("stationClass").asInt(-1) != 1
                    || !sameBusName(stop.path("stationName").asText(""), name)
                    || stop.path("stationID").asText("").isBlank()) continue;
            double sx = stop.path("x").asDouble(Double.NaN);
            double sy = stop.path("y").asDouble(Double.NaN);
            if (!Double.isFinite(sx) || !Double.isFinite(sy)
                    || Math.abs(sx - x) > 0.002 || Math.abs(sy - y) > 0.002) continue;
            matches.add(stop);
        }
        return matches.stream().limit(5).toList();
    }

    private static List<JsonNode> odsayRows(JsonNode value) {
        if (value.isArray()) {
            List<JsonNode> rows = new ArrayList<>();
            value.forEach(rows::add);
            return rows;
        }
        return value.isObject() ? List.of(value) : List.of();
    }

    private static boolean sameBusName(String left, String right) {
        if (left == null || right == null) return false;
        // Permit punctuation/spacing differences, but not partial station names.
        String a = left.replaceAll("[^\\p{L}\\p{N}]", "");
        String b = right.replaceAll("[^\\p{L}\\p{N}]", "");
        return !a.isBlank() && a.equalsIgnoreCase(b);
    }

    /** Latest estimated connected departure in an operating interval already in progress. */
    public LocalDateTime previewCurrentLastDeparture(TransitDto.RouteOptionResponse route,
                                                    LocalDateTime now,
                                                    Map<String, LocalDateTime> scheduleCache) {
        try {
            RouteSchedulePlan plan = calculateCurrentLastPlan(route, now, scheduleCache);
            return plan == null ? null : plan.departure();
        } catch (com.OnETA.common.exception.GlobalException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
        }
    }

    private RouteSchedulePlan calculateCurrentLastPlan(TransitDto.RouteOptionResponse route,
                                                       LocalDateTime now,
                                                       Map<String, LocalDateTime> scheduleCache) {
        if (route == null || route.getSegments() == null || route.getSegments().isEmpty()) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
        }
        // Use the last *connected* train pair, not independent last trains whose
        // upstream/downstream departures may not form a reachable transfer.
        if (!now.toLocalTime().isBefore(LocalTime.of(6, 0))
                && route.getSegments().stream().filter(segment ->
                    segment != null && "SUBWAY".equals(segment.getTransitType())).count() >= 2
                && !OdsayConnectedLastSubway.supports(route)) {
            log.info("Connected subway LAST not eligible: routeId={}, provider={}, "
                            + "subwaySegments={}, missingIdsOrUnsupportedRoute=true",
                    route.getRouteId(), route.getProvider(),
                    route.getSegments().stream().filter(segment ->
                            segment != null && "SUBWAY".equals(segment.getTransitType())).count());
        }
        if (!now.toLocalTime().isBefore(LocalTime.of(6, 0))
                && OdsayConnectedLastSubway.supports(route)) {
            var connected = resolveConnectedLastSubway(route, now.toLocalDate());
            if (connected.isPresent()) {
                var departure = connected.get();
                return departure.isAfter(now) ? connectedLastPlan(route, departure) : null;
            }
        }
        boolean seoul = SeoulBusScheduleService.usesSeoulBusSchedules(route);
        if (SeoulBusScheduleService.isKakao(route) && pureSubwayTransfer(route)
                && seoulMetroTrainScheduleService != null) {
            LocalDate serviceDay = now.toLocalTime().isBefore(LocalTime.of(6, 0))
                    ? now.toLocalDate().minusDays(1) : now.toLocalDate();
            try {
                var exact = calculateVerifiedKakaoSubwayLastPlan(route, serviceDay);
                return exact.departure().isAfter(now) ? exact : null;
            } catch (com.OnETA.common.exception.GlobalException e) {
                if (e.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_CONNECTION_UNVERIFIED) {
                    throw e;
                }
                log.info("Verified current LAST unavailable, retaining conservative path: routeId={}, date={}, code={}",
                        route.getRouteId(), serviceDay, e.getErrorCode().getCode());
            }
        }
        boolean nightOnly = TransitRouteClassifier.isNightOnlyRoute(route);
        boolean verifiedSubwayTransfer = seoul && pureSubwayTransfer(route);
        boolean singleBus = route.getSegments().stream()
                .filter(segment -> segment != null && !"WALK".equals(segment.getTransitType())).count() == 1
                && route.getSegments().stream().anyMatch(segment -> segment != null && "BUS".equals(segment.getTransitType()));
        List<TransitOperatingWindow> windows = new ArrayList<>();
        List<SeoulBusScheduleService.Schedule> bindings = new ArrayList<>();
        for (var segment : route.getSegments()) {
            if (segment == null || segment.getDurationMinutes() == null || segment.getDurationMinutes() < 0) {
                throw connectionUnverified();
            }
            if ("WALK".equals(segment.getTransitType())) continue;
            TransitOperatingWindow window;
            if (seoul) {
                var binding = "SUBWAY".equals(segment.getTransitType())
                        ? (verifiedSubwayTransfer
                                ? resolveKakaoSubwayScheduleForLast(segment, now.toLocalDate())
                                : resolveKakaoSubwaySchedule(segment, now.toLocalDate()))
                        : resolveKakaoBusSchedule(singleBus ? route : route.toBuilder().transferCount(0)
                                .segments(List.of(segment)).build(), now.toLocalDate());
                window = new TransitOperatingWindow(binding.first(), binding.last());
                if ("BUS".equals(segment.getTransitType())) {
                    window = window.at(now);
                    if (nightOnly) window = window.alignUpcomingNightBus(now);
                } else if (now.isBefore(window.first())) {
                    // Subway APIs accept a service date: use the actual preceding
                    // weekday timetable instead of shifting today's timetable.
                    var preceding = verifiedSubwayTransfer
                            ? resolveKakaoSubwayScheduleForLast(segment, now.toLocalDate().minusDays(1))
                            : resolveKakaoSubwaySchedule(segment, now.toLocalDate().minusDays(1));
                    var previousWindow = new TransitOperatingWindow(preceding.first(), preceding.last());
                    if (previousWindow.contains(now)) {
                        binding = preceding;
                        window = previousWindow;
                    }
                }
                bindings.add(new SeoulBusScheduleService.Schedule(binding.stationId(), binding.arsId(),
                        binding.routeId(), window.first(), window.last(), binding.order(),
                        binding.endStationId(), binding.endOrder()));
            } else {
                LocalDate date = now.toLocalDate();
                LocalDateTime first = serviceTimeCached(segment, NotificationScheduleType.FIRST_TRANSIT, date, scheduleCache);
                if (first == null) throw new com.OnETA.common.exception.GlobalException(
                        com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
                LocalDateTime last = serviceTimeCached(segment, NotificationScheduleType.LAST_TRANSIT, date, scheduleCache);
                if (last == null) throw new com.OnETA.common.exception.GlobalException(
                        com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
                window = new TransitOperatingWindow(first, last);
                if ("BUS".equals(segment.getTransitType())) {
                    window = window.at(now);
                    if (nightOnly) window = window.alignUpcomingNightBus(now);
                } else if ("SUBWAY".equals(segment.getTransitType()) && now.isBefore(first)) {
                    var previousFirst = serviceTimeCached(segment, NotificationScheduleType.FIRST_TRANSIT,
                            date.minusDays(1), scheduleCache);
                    var previousLast = serviceTimeCached(segment, NotificationScheduleType.LAST_TRANSIT,
                            date.minusDays(1), scheduleCache);
                    if (previousFirst != null && previousLast != null) {
                        var previousWindow = new TransitOperatingWindow(previousFirst, previousLast);
                        if (previousWindow.contains(now)) window = previousWindow;
                    }
                }
            }
            if (!window.last().isAfter(window.first())) throw connectionUnverified();
            if (log.isDebugEnabled()) {
                log.debug("LAST operating window: routeId={}, provider={}, leg={}, line={}, from={}, to={}, first={}, last={}",
                        route.getRouteId(), route.getProvider(), windows.size(), segment.getTransitName(),
                        segment.getStartStation(), segment.getEndStation(), window.first(), window.last());
            }
            windows.add(window);
        }
        if (windows.isEmpty()) throw connectionUnverified();
        if (nightOnly) {
            log.info("NIGHT_LAST_WINDOW routeId={}, now={}, intervals={}",
                    route.getRouteId(), now, windows);
        }
        // The origin ride must be running now, except an N-only route whose first
        // bus starts later in this same night. All projected boardings must still
        // be within their respective real/recurring operating intervals.
        if (!windows.get(0).contains(now)
                && !(nightOnly && windows.get(0).catchableThisNight(now))) {
            if (nightOnly) {
                log.info("NIGHT_LAST_NULL routeId={}, reason=FIRST_RIDE_NOT_ACTIVE, now={}, first={}, last={}",
                        route.getRouteId(), now, windows.get(0).first(), windows.get(0).last());
            }
            return null;
        }
        List<LocalDateTime> lastTimes = windows.stream().map(TransitOperatingWindow::last).toList();
        if (windows.size() > 1 && route.getSegments().stream().anyMatch(segment ->
                segment != null && "SUBWAY".equals(segment.getTransitType()))) {
            log.info("LAST transfer conservative fallback: routeId={}, provider={}, "
                            + "subwayLines={}, lastBoundaries={}",
                    route.getRouteId(), route.getProvider(),
                    route.getSegments().stream().filter(segment ->
                            segment != null && "SUBWAY".equals(segment.getTransitType()))
                            .map(TransitDto.RouteSegment::getTransitName).toList(),
                    lastTimes);
        }
        var plan = windows.size() == 1
                ? TransitScheduleCalculator.calculate(route.getSegments(), lastTimes, NotificationScheduleType.LAST_TRANSIT)
                : TransitScheduleCalculator.conservative(route.getSegments(), lastTimes, NotificationScheduleType.LAST_TRANSIT);
        if (log.isDebugEnabled()) {
            log.debug("LAST route departure: routeId={}, provider={}, calculated={}, referenceNow={}, numberOfRides={}",
                    route.getRouteId(), route.getProvider(), plan.departure(), now, windows.size());
        }
        if (!plan.departure().isAfter(now)) {
            if (nightOnly) {
                log.info("NIGHT_LAST_NULL routeId={}, reason=ESTIMATED_DEPARTURE_PASSED, now={}, departure={}",
                        route.getRouteId(), now, plan.departure());
            }
            return null;
        }
        int prefix = 0, ride = 0;
        for (var segment : route.getSegments()) {
            if (!"WALK".equals(segment.getTransitType())) {
                var window = windows.get(ride++);
                LocalDateTime boarding = plan.departure().plusMinutes(prefix + (windows.size() > 1 ? 5 : 0));
                if (boarding.isBefore(window.first()) || boarding.isAfter(window.last())) {
                    if (nightOnly) {
                        log.info("NIGHT_LAST_NULL routeId={}, reason=BOARDING_OUTSIDE_OPERATING_WINDOW, "
                                        + "boarding={}, first={}, last={}",
                                route.getRouteId(), boarding, window.first(), window.last());
                    }
                    return null;
                }
                if (windows.size() > 1) {
                    prefix += Math.max(3, (int) Math.ceil(segment.getDurationMinutes() * 0.25));
                    if (ride < windows.size()) prefix += 10;
                }
            }
            prefix += segment.getDurationMinutes();
        }
        String source = seoul ? (singleBus ? "SEOUL_BUS" : "SEOUL_BUS_TRANSFER") : "ODSAY";
        return new RouteSchedulePlan(plan.departure(), plan.durationMinutes(), source,
                seoul && !singleBus ? objectMapper.writeValueAsString(bindings) : null,
                !singleBus ? Math.max(60, prefix) : 0);
    }

    private Optional<LocalDateTime> resolveConnectedLastSubway(
            TransitDto.RouteOptionResponse route, LocalDate day) {
        var mid = OdsayConnectedLastSubway.transferId(route);
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("SID", OdsayConnectedLastSubway.originId(route));
            params.put("EID", OdsayConnectedLastSubway.destinationId(route));
            params.put("MODE", "4");
            params.put("DAY", odsayDay(day));
            mid.ifPresent(value -> params.put("MID", value));
            JsonNode timetable = request("/subwayPathSchedule", params);
            ensureNoOdsayError(timetable);
            var departure = OdsayConnectedLastSubway.find(route, timetable, day);
            JsonNode paths = timetable.path("result").path("path");
            log.info("Connected subway LAST: routeId={}, provider={}, date={}, midProvided={}, "
                            + "pathCount={}, matched={}, rejection={}, selected={}",
                    route.getRouteId(), route.getProvider(), day, mid.isPresent(),
                    paths.isArray() ? paths.size() : 0, departure.isPresent(),
                    departure.isPresent() ? "NONE" : OdsayConnectedLastSubway.mismatchReason(route, timetable),
                    departure.orElse(null));
            return departure;
        } catch (RuntimeException e) {
            log.warn("Connected subway LAST unavailable; retaining existing conservative estimate: "
                            + "routeId={}, provider={}, date={}, midProvided={}, errorType={}, errorCode={}",
                    route.getRouteId(), route.getProvider(), day, mid.isPresent(),
                    e.getClass().getSimpleName(),
                    e instanceof com.OnETA.common.exception.GlobalException global
                            ? global.getErrorCode().getCode() : "EXTERNAL_API_OR_RESPONSE");
            return Optional.empty();
        }
    }

    private RouteSchedulePlan connectedLastPlan(
            TransitDto.RouteOptionResponse route, LocalDateTime departure) {
        int duration = route.getTotalDurationMinutes() == null
                ? route.getSegments().stream().map(TransitDto.RouteSegment::getDurationMinutes)
                        .filter(Objects::nonNull).mapToInt(Integer::intValue).sum()
                : route.getTotalDurationMinutes();
        int safeDuration = Math.max(1, duration);
        return new RouteSchedulePlan(departure, safeDuration, "ODSAY", null,
                Math.max(15, Math.min(60, safeDuration)));
    }

    public LocalDateTime previewDepartureForServiceDate(TransitDto.RouteOptionResponse route,
                                                        NotificationScheduleType type,
                                                        LocalDate serviceDate,
                                                        Map<String, LocalDateTime> scheduleCache) {
        if (type != NotificationScheduleType.FIRST_TRANSIT
                && type != NotificationScheduleType.LAST_TRANSIT) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE);
        }
        try {
            return calculateRoutePlan(route, type, serviceDate, scheduleCache).departure();
        } catch (com.OnETA.common.exception.GlobalException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
        }
    }

    public LocalDateTime previewNextDeparture(TransitDto.RouteOptionResponse route,
                                              NotificationScheduleType type,
                                              LocalDateTime now,
                                              ZoneId zone) {
        return previewNextDeparture(route, type, now, zone, new HashMap<>());
    }

    public LocalDateTime previewNextDeparture(TransitDto.RouteOptionResponse route,
                                              NotificationScheduleType type,
                                              LocalDateTime now,
                                              ZoneId zone,
                                              Map<String, LocalDateTime> scheduleCache) {
        if (type != NotificationScheduleType.FIRST_TRANSIT
                && type != NotificationScheduleType.LAST_TRANSIT) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE);
        }
        com.OnETA.common.exception.GlobalException unavailable = null;
        com.OnETA.common.exception.GlobalException unsupported = null;
        LocalDate today = now.toLocalDate();

        if (type == NotificationScheduleType.LAST_TRANSIT) {
            LocalDateTime current = previewCurrentLastDeparture(route, now, scheduleCache);
            if (current != null) return current;
        }

        for (LocalDate day = today; !day.isAfter(today.plusDays(1)); day = day.plusDays(1)) {
            try {
                RouteSchedulePlan plan = calculateRoutePlan(route, type, day, scheduleCache);
                if (plan.departure().isAfter(now)) return plan.departure();
            } catch (com.OnETA.common.exception.GlobalException ex) {
                if (ex.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE) {
                    unavailable = ex;
                } else if (ex.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED
                        || ex.getErrorCode() == com.OnETA.common.error.ErrorCode.TRANSIT_CONNECTION_UNVERIFIED) {
                    unsupported = ex;
                } else {
                    throw ex;
                }
            } catch (RuntimeException ex) {
                unavailable = new com.OnETA.common.exception.GlobalException(
                        com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
            }
        }

        if (unavailable != null) throw unavailable;
        if (unsupported != null) throw unsupported;
        throw new com.OnETA.common.exception.GlobalException(
                com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
    }

    /**
     * Persist the departure that the user actually chose in first/last search.
     * A 00:xx-05:xx LAST departure belongs to the preceding operating day,
     * so the scheduler can still deliver it after midnight.
     */
    @Transactional
    public void pinSelectedDeparture(ArrivalNotification notification, OffsetDateTime selectedAt, ZoneId zone) {
        NotificationScheduleType type = notification.getScheduleType();
        if (type != NotificationScheduleType.FIRST_TRANSIT && type != NotificationScheduleType.LAST_TRANSIT) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE, "첫차·막차 경로에서만 출발시각을 선택할 수 있습니다.");
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), zone);
        LocalDateTime departure = selectedAt.atZoneSameInstant(zone).toLocalDateTime();
        LocalTime time = departure.toLocalTime();
        boolean inWindow = type == NotificationScheduleType.LAST_TRANSIT
                ? !time.isBefore(LocalTime.of(21, 0)) || !time.isAfter(LocalTime.of(6, 0))
                : !time.isBefore(LocalTime.of(3, 0)) && !time.isAfter(LocalTime.of(9, 0));
        if (!departure.isAfter(now) || departure.isAfter(now.plusDays(2)) || !inWindow) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "선택한 첫차·막차 출발시각이 유효하지 않습니다. 경로를 다시 조회해주세요.");
        }

        TransitDto.RouteOptionResponse route = transitApiService.readSavedRoute(notification.getRouteDetails());
        Integer duration = route.getTotalDurationMinutes();
        if (duration == null || duration <= 0 || duration > 1440) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE, "선택한 경로의 소요시간이 유효하지 않습니다.");
        }
        LocalDate serviceDate = departure.toLocalDate();
        if (type == NotificationScheduleType.LAST_TRANSIT && time.isBefore(LocalTime.of(6, 0))) {
            serviceDate = serviceDate.minusDays(1);
        }
        int offset = notification.getReminderOffsetMinutesList().stream()
                .max(Integer::compareTo).orElse(notification.getReminderOffsetMinutes());
        LocalDateTime scheduled = departure.minusMinutes(offset);
        int evaluationLead = Math.max(15, Math.min(60, duration));
        ScheduleSnapshot snapshot = new ScheduleSnapshot(notification, serviceDate, type,
                hash(notification.getRouteDetails(), type), departure, scheduled,
                scheduled.minusMinutes(evaluationLead), LocalDateTime.now(ZoneOffset.UTC), duration);
        snapshot.useSelectedPreviewSource();
        snapshotRepository.save(snapshot);
    }

    /**
     * A pinned result must not disable the existing live Seoul-transfer checks.
     * Resolve provider bindings when the reminder window approaches; if an API is
     * unavailable, keep the selected time instead of silently jumping to tonight.
     */
    private void refreshSelectedPreviewSource(ScheduleSnapshot snapshot, ArrivalNotification notification,
                                              LocalDateTime now) {
        if (!"SELECTED_PREVIEW".equals(snapshot.getSource())
                || notification.getScheduleType() != NotificationScheduleType.LAST_TRANSIT
                || now.isBefore(snapshot.getRealtimeEvaluationStartAt())
                || !snapshot.getEffectiveDepartureAt().isAfter(now)) return;

        TransitDto.RouteOptionResponse route = transitApiService.readSavedRoute(notification.getRouteDetails());
        if (!SeoulBusScheduleService.usesSeoulBusSchedules(route)) return;
        LocalDateTime lastAttempt = snapshot.getLastRealtimeEvaluatedAt();
        if (lastAttempt != null && now.isBefore(lastAttempt.plusMinutes(3))) return;

        // Throttle external lookups, even when the provider fails.
        snapshot.updateConnection(snapshot.getEffectiveDepartureAt(), snapshot.getEffectiveScheduledAt(),
                snapshot.getEstimatedDurationMinutes(), now);
        try {
            RouteSchedulePlan current = calculateCurrentLastPlan(route, now, new HashMap<>());
            if (current != null && Math.abs(Duration.between(current.departure(),
                    snapshot.getEffectiveDepartureAt()).toMinutes()) <= 15) {
                if ("SEOUL_BUS_TRANSFER".equals(current.source()) && current.providerDetails() != null) {
                    snapshot.useSeoulTransferSource(current.providerDetails());
                } else if ("SEOUL_BUS".equals(current.source())) {
                    snapshot.useSeoulBusSource();
                }
            }
        } catch (RuntimeException e) {
            log.debug("Selected last departure retained while live bindings unavailable: notificationId={}, reason={}",
                    notification.getId(), e.getClass().getSimpleName());
        }
        snapshotRepository.save(snapshot);
    }

    /** True while a selected/known LAST run from yesterday remains catchable after midnight. */
    public boolean hasPendingPreviousLast(ArrivalNotification notification, LocalDateTime now) {
        if (notification.getScheduleType() != NotificationScheduleType.LAST_TRANSIT) return false;
        return snapshotRepository.findByNotificationIdAndServiceDateAndScheduleTypeAndRouteHash(
                notification.getId(), now.toLocalDate().minusDays(1),
                NotificationScheduleType.LAST_TRANSIT, hash(notification.getRouteDetails(), NotificationScheduleType.LAST_TRANSIT))
                .map(snapshot -> snapshot.getEffectiveDepartureAt().isAfter(now))
                .orElse(false);
    }

    /** Read-only display estimate: never runs realtime polling, recovery, or delivery creation. */
    public LocalDateTime estimateDeparture(ArrivalNotification notification, LocalDateTime now, ZoneId zone) {
        String routeHash = hash(notification.getRouteDetails(), notification.getScheduleType());
        var type = notification.getScheduleType();

        // Reuse a persisted snapshot only while it is still in the future. If the latest
        // service-day snapshot has already passed but the setting is still active, preview the
        // next service day instead of returning a stale time or deactivating the setting.
        var saved = snapshotRepository
                .findFirstByNotificationIdAndScheduleTypeAndRouteHashOrderByServiceDateDesc(
                        notification.getId(), type, routeHash);
        LocalDate today = now.toLocalDate();
        if (type == NotificationScheduleType.LAST_TRANSIT) {
            var previous = snapshotRepository.findByNotificationIdAndServiceDateAndScheduleTypeAndRouteHash(
                    notification.getId(), today.minusDays(1), type, routeHash);
            if (previous.isPresent() && previous.get().getEffectiveDepartureAt().isAfter(now)) {
                return previous.get().getEffectiveDepartureAt();
            }
        }
        // A tomorrow snapshot may already exist (for example after a scheduler
        // refresh). Prefer today's still-catchable selected departure, not the
        // most recently stored service date. The screen shows only HH:mm.
        var current = snapshotRepository.findByNotificationIdAndServiceDateAndScheduleTypeAndRouteHash(
                notification.getId(), today, type, routeHash);
        if (current.isPresent() && current.get().getEffectiveDepartureAt().isAfter(now)) {
            return current.get().getEffectiveDepartureAt();
        }
        if (saved.isEmpty() && type == NotificationScheduleType.LAST_TRANSIT) {
            saved = snapshotRepository.findFirstByNotificationIdAndScheduleTypeAndRouteHashOrderByServiceDateDesc(
                    notification.getId(), type, hash(notification.getRouteDetails()))
                    .filter(snapshot -> snapshot.getServiceDate().isBefore(today)
                            && snapshot.getEffectiveDepartureAt().isAfter(now));
        }
        LocalDate startDay = today;
        if (saved.isPresent()) {
            LocalDateTime departure = saved.get().getEffectiveDepartureAt();
            if (departure.isAfter(now)) {
                return departure;
            }
            startDay = saved.get().getServiceDate().plusDays(1);
            if (startDay.isBefore(today)) startDay = today;
        }

        // A setting that was actually completed by the delivery pipeline must not be rolled
        // forward. Missing FCM tokens do not complete a setting, so those remain active.
        if (Boolean.FALSE.equals(notification.getIsActive())) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
        }

        LocalDate endDay = today.plusDays(1);
        for (LocalDate day = startDay; !day.isAfter(endDay); day = day.plusDays(1)) {
            ScheduleSnapshot snapshot = buildSnapshot(notification, day, type, routeHash, zone, now);
            LocalDateTime departure = snapshot.getEffectiveDepartureAt();
            if (departure.isAfter(now)) {
                return departure;
            }
        }

        throw new com.OnETA.common.exception.GlobalException(
                com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
    }

    private Decision evaluateSeoulTransfer(ScheduleSnapshot snapshot, ArrivalNotification n,
                                            LocalDateTime now, NotificationScheduleType type, int offset) {
        if (now.isBefore(snapshot.getRealtimeEvaluationStartAt())) return null;
        // Bounded polling; no midday recovery of a missed first service.
        if (now.isAfter(snapshot.getBaseDepartureAt().plusMinutes(60))) return null;
        var route = transitApiService.readSavedRoute(n.getRouteDetails());
        var schedules = objectMapper.readValue(snapshot.getProviderDetails(), SeoulBusScheduleService.Schedule[].class);
        List<List<SeoulBusScheduleService.LiveBus>> arrivals = new ArrayList<>();
        for (var schedule : schedules) {
            try {
            arrivals.add(seoulBusScheduleService.arrivals(schedule, now).stream()
                    .filter(bus -> !bus.boarding().isBefore(schedule.first().minusMinutes(5))
                            && !bus.boarding().isAfter(schedule.last().plusMinutes(60)))
                    .toList());
            } catch (RuntimeException e) {
                log.debug("Using conservative timetable after live lookup failure: notificationId={}", n.getId());
                arrivals.add(List.of());
            }
        }
        List<LocalDateTime> observedBounds = new ArrayList<>();
        boolean earlierObserved = false;
        for (int i = 0; i < schedules.length; i++) {
            LocalDateTime boundary = type == NotificationScheduleType.FIRST_TRANSIT ? schedules[i].first() : schedules[i].last();
            var candidates = arrivals.get(i).stream()
                    .filter(bus -> type == NotificationScheduleType.FIRST_TRANSIT || bus.last())
                    .map(SeoulBusScheduleService.LiveBus::boarding).min(LocalDateTime::compareTo);
            if (candidates.isPresent() && candidates.get().isBefore(boundary)) {
                boundary = candidates.get();
                earlierObserved = true;
            }
            observedBounds.add(boundary);
        }
        var earlyEstimate = TransitScheduleCalculator.conservative(route.getSegments(), observedBounds, type);
        var plan = TransitScheduleCalculator.live(route.getSegments(), arrivals, type, now);
        // Missing live data must not suppress a conservative early-departure alert.
        // Live observations can advance the alert but must never postpone it.
        if (plan == null || plan.departure().isAfter(snapshot.getEffectiveDepartureAt())) {
            plan = new TransitScheduleCalculator.Plan(snapshot.getEffectiveDepartureAt(), snapshot.getEstimatedDurationMinutes());
        }
        if (earlierObserved && earlyEstimate.departure().isBefore(plan.departure())) plan = earlyEstimate;
        if (!plan.departure().isAfter(now)) return null;
        LocalDateTime scheduled = plan.departure().minusMinutes(offset);
        snapshot.updateConnection(plan.departure(), scheduled, plan.durationMinutes(), now);
        snapshotRepository.save(snapshot);
        return new Decision(scheduled, plan.departure(), DeliveryPhase.BASE, snapshot.getBaseDepartureAt(),
                plan.departure(), false, plan.durationMinutes());
    }

    private void evaluateFirstSafety(ScheduleSnapshot snapshot, ArrivalNotification n, LocalDateTime now, ZoneId zone) {
        TransitDto.RouteOptionResponse route = transitApiService.readSavedRoute(n.getRouteDetails());
        int prefix = 0; LocalDateTime earliest = snapshot.getEffectiveDepartureAt();
        for (TransitDto.RouteSegment s : route.getSegments()) {
            if ("BUS".equals(s.getTransitType()) && s.getScheduledWaitMinutes() != null) {
                try {
                    PublicDataTransitService.ArrivalEstimate a = publicDataTransitService.findArrival(
                            s.getLocalCityCode(), s.getLocalRouteId(), s.getLocalStationId(), s.getArsId(),
                            s.getTransitName(), s.getStartStation(), s.getStartX(), s.getStartY());
                    if (a != null) {
                        int wait = Math.max(1, (int) Math.ceil(a.arrivalSeconds() / 60.0));
                        int delta = Math.max(0, s.getScheduledWaitMinutes() - wait);
                        earliest = earliest.minusMinutes(delta);
                    }
                }
                catch (RuntimeException ignored) { }
            }
            prefix += Math.max(0, s.getDurationMinutes() == null ? 0 : s.getDurationMinutes());
        }
        LocalDateTime scheduled = earliest.minusMinutes(n.getReminderOffsetMinutesList().stream().max(Integer::compareTo).orElse(0));
        snapshot.markRealtime(earliest, scheduled, now);
        snapshotRepository.save(snapshot);
    }

    private RecoveryCandidate findRecoveryCandidate(ScheduleSnapshot snapshot, ArrivalNotification n, LocalDateTime now, ZoneId zone) {
        TransitDto.RouteOptionResponse route = transitApiService.readSavedRoute(n.getRouteDetails());
        int prefix = 0;
        int offset = n.getReminderOffsetMinutesList().stream().max(Integer::compareTo).orElse(0);
        for (TransitDto.RouteSegment s : route.getSegments()) {
            if ("BUS".equals(s.getTransitType())) {
                try {
                    PublicDataTransitService.ArrivalEstimate a = publicDataTransitService.findArrival(
                            s.getLocalCityCode(), s.getLocalRouteId(), s.getLocalStationId(), s.getArsId(),
                            s.getTransitName(), s.getStartStation(), s.getStartX(), s.getStartY());
                    if (a != null && a.arrivalSeconds() > 0) {
                        LocalDateTime boarding = now.plusSeconds(a.arrivalSeconds());
                        LocalDateTime departure = boarding.minusMinutes(prefix);
                        return new RecoveryCandidate(boarding, departure.minusMinutes(offset));
                    }
                } catch (RuntimeException e) {
                    throw e;
                }
            }
            prefix += Math.max(0, s.getDurationMinutes() == null ? 0 : s.getDurationMinutes());
        }
        return null;
    }

    private LocalDateTime serviceTimeCached(TransitDto.RouteSegment segment,
                                            NotificationScheduleType type,
                                            LocalDate serviceDate,
                                            Map<String, LocalDateTime> scheduleCache) {
        if (scheduleCache == null) return serviceTime(segment, type, serviceDate, null);
        String key = scheduleKey(segment, type, serviceDate);
        if (scheduleCache.containsKey(key)) return scheduleCache.get(key);
        LocalDateTime resolved = serviceTime(segment, type, serviceDate, scheduleCache);
        if (resolved != null) scheduleCache.put(key, resolved);
        return resolved;
    }

    private String scheduleKey(TransitDto.RouteSegment segment, NotificationScheduleType type, LocalDate serviceDate) {
        return type + "|" + serviceDate + "|" + segment.getTransitType() + "|"
                + Objects.toString(segment.getOdsayStartStationId(), "") + "|"
                + Objects.toString(segment.getOdsayEndStationId(), "") + "|"
                + Objects.toString(segment.getOdsayRouteId(), "") + "|"
                + Objects.toString(segment.getLocalRouteId(), "");
    }

    private String odsayDay(LocalDate serviceDate) {
        return KoreanSubwayServiceDay.odsayDay(serviceDate);
    }

    private LocalDateTime serviceTime(TransitDto.RouteSegment s, NotificationScheduleType type, LocalDate serviceDate,
                                     Map<String, LocalDateTime> scheduleCache) {
        if ("BUS".equals(s.getTransitType())) {
            JsonNode response = request("/busStationInfo", Map.of("stationID", s.getOdsayStartStationId()));
            logBusStationInfoResponse(response, s.getOdsayStartStationId());
            ensureNoOdsayError(response);
            JsonNode result = response.path("result");
            JsonNode lanes = result.path("lane");
            int laneCount = lanes.isArray() ? lanes.size() : 0;
            boolean matched = false;
            LocalDateTime serviceTime = null;
            if (lanes.isArray()) for (JsonNode lane : lanes) {
                if (same(lane.path("busID"), s.getOdsayRouteId())
                        || same(lane.path("busLocalBlID"), s.getLocalRouteId())) {
                    matched = true;
                    // FIRST and LAST come from the same busStationInfo response.
                    // Cache both boundaries so current-window checks do not double API calls.
                    LocalDateTime busFirst = parseTime(lane.path("busFirstTime").asText(null), serviceDate);
                    LocalDateTime busLast = parseTime(lane.path("busLastTime").asText(null), serviceDate);
                    if (scheduleCache != null) {
                        if (busFirst != null && busLast != null && busLast.isBefore(busFirst)) busLast = busLast.plusDays(1);
                        scheduleCache.put(scheduleKey(s, NotificationScheduleType.FIRST_TRANSIT, serviceDate), busFirst);
                        scheduleCache.put(scheduleKey(s, NotificationScheduleType.LAST_TRANSIT, serviceDate), busLast);
                    }
                    String field = type == NotificationScheduleType.FIRST_TRANSIT ? "busFirstTime" : "busLastTime";
                    serviceTime = parseTime(lane.path(field).asText(null), serviceDate);
                    if (type == NotificationScheduleType.LAST_TRANSIT && serviceTime != null) {
                        LocalDateTime first = parseTime(lane.path("busFirstTime").asText(null), serviceDate);
                        // 00:xx belongs to the end of this service day, just like 24:xx.
                        if (first == null) throw new com.OnETA.common.exception.GlobalException(
                                com.OnETA.common.error.ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
                        if (serviceTime.isBefore(first)) serviceTime = serviceTime.plusDays(1);
                    }
                    break;
                }
            }
            log.debug("BUS schedule lookup: scheduleType={}, stationId={}, odsayRouteId={}, localRouteId={}, laneCount={}, routeMatched={}, serviceTimePresent={}",
                    type, s.getOdsayStartStationId(), s.getOdsayRouteId(), s.getLocalRouteId(), laneCount, matched, serviceTime != null);
            return serviceTime;
        }
        if ("SUBWAY".equals(s.getTransitType())) {
            try {
                JsonNode root = request("/subwayPathSchedule", Map.of(
                        "SID", s.getOdsayStartStationId(), "EID", s.getOdsayEndStationId(),
                        "MODE", type == NotificationScheduleType.FIRST_TRANSIT ? "3" : "4",
                        "DAY", odsayDay(serviceDate)));
                ensureNoOdsayError(root);
                return findTime(root, type == NotificationScheduleType.FIRST_TRANSIT, serviceDate);
            } catch (OdsayQuotaExceededException quota) {
                log.warn("ODsay subway quota exceeded; using TAGO timetable: scheduleType={}, serviceDate={}",
                        type, serviceDate);
                try {
                    var schedule = tagoSubwayScheduleService.resolve(s, serviceDate);
                    return type == NotificationScheduleType.FIRST_TRANSIT ? schedule.first() : schedule.last();
                } catch (RuntimeException fallbackFailure) {
                    fallbackFailure.addSuppressed(quota);
                    throw fallbackFailure;
                }
            }
        }
        return null;
    }

    /**
     * Logs only the shape and schedule fields needed to diagnose an ODsay response.
     * In particular, do not log the request URI here because it contains the API key.
     */
    private void logBusStationInfoResponse(JsonNode response, String stationId) {
        JsonNode result = response.path("result");
        JsonNode lanes = result.path("lane");
        JsonNode error = response.path("error");
        JsonNode errorEntry = firstError(error);
        String errorCode = textOrNull(errorEntry.path("code"));
        String errorMessage = textOrNull(errorEntry.path("message"));

        log.debug("BUS station response: stationId={}, resultPresent={}, resultType={}, "
                        + "resultFields={}, lanePresent={}, laneType={}, laneSize={}, errorCode={}, errorMessage={}",
                stationId,
                !result.isMissingNode(),
                nodeType(result),
                fieldNames(result),
                !lanes.isMissingNode(),
                nodeType(lanes),
                lanes.isArray() ? lanes.size() : null,
                errorCode,
                errorMessage);

        if (lanes.isArray()) {
            for (int i = 0; i < lanes.size(); i++) {
                JsonNode lane = lanes.get(i);
                log.debug("BUS station lane: stationId={}, laneIndex={}, busID={}, busLocalBlID={}, "
                                + "busNo={}, busFirstTime={}, busLastTime={}",
                        stationId, i,
                        textOrNull(lane.path("busID")),
                        textOrNull(lane.path("busLocalBlID")),
                        textOrNull(lane.path("busNo")),
                        textOrNull(lane.path("busFirstTime")),
                        textOrNull(lane.path("busLastTime")));
            }
        }
    }

    private String nodeType(JsonNode node) {
        if (node.isMissingNode()) return "MISSING";
        if (node.isNull()) return "NULL";
        if (node.isArray()) return "ARRAY";
        if (node.isObject()) return "OBJECT";
        return "VALUE";
    }

    private String fieldNames(JsonNode node) {
        if (!node.isObject()) return "[]";
        return node.propertyNames().toString();
    }

    private String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private JsonNode firstError(JsonNode error) {
        if (error.isArray()) return error.isEmpty() ? objectMapper.missingNode() : error.get(0);
        return error;
    }

    private void ensureNoOdsayError(JsonNode response) {
        JsonNode error = response.path("error");
        JsonNode errorEntry = firstError(error);
        if (errorEntry.isMissingNode() || errorEntry.isNull() || !errorEntry.isObject()) return;

        String code = textOrNull(errorEntry.path("code"));
        String message = textOrNull(errorEntry.path("message"));
        String detail = "ODsay schedule API error: code="
                + (code == null ? "unknown" : code)
                + ", message=" + (message == null ? "unknown" : message);
        if ("429".equals(code) || (message != null
                && message.toLowerCase(Locale.ROOT).contains("daily quota exceeded"))) {
            throw new OdsayQuotaExceededException(detail, null);
        }
        throw new IllegalStateException(detail);
    }

    boolean isApiKeyConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    private JsonNode request(String path, Map<String,String> params) {
        log.debug("odsayApiKeyConfigured={}", isApiKeyConfigured());
        log.debug("odsayApiKeyValuesMatch={}", transitApiService.hasSameOdsayApiKey(apiKey));
        String normalizedApiKey = apiKey == null ? "" : apiKey.trim();
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(scheduleBaseUrl + path)
                .queryParam("apiKey", normalizedApiKey);
        params.forEach(b::queryParam);
        URI uri = b.encode(StandardCharsets.UTF_8).build().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.REFERER, normalizeReferer(odsayReferer));
        headers.set(HttpHeaders.ORIGIN, originFromReferer(odsayReferer));
        log.debug("ODsay schedule request: host={}, endpoint={}", uri.getHost(), uri.getPath());
        try {
            com.OnETA.common.ExternalApiCallCounter.record("ODSAY", path);
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return objectMapper.readTree(response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 429) {
                throw new OdsayQuotaExceededException("ODsay schedule API HTTP 429", e);
            }
            throw new IllegalStateException("운행정보 조회 실패", e);
        } catch (Exception e) { throw new IllegalStateException("운행정보 조회 실패", e); }
    }

    private static final class OdsayQuotaExceededException extends IllegalStateException {
        private OdsayQuotaExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String normalizeReferer(String referer) {
        String value = referer == null || referer.isBlank() ? "http://localhost:8080/" : referer.trim();
        return value.endsWith("/") ? value : value + "/";
    }

    private static String originFromReferer(String referer) {
        URI uri = URI.create(normalizeReferer(referer));
        return uri.getScheme() + "://" + uri.getAuthority();
    }
    private LocalDateTime findTime(JsonNode node, boolean first, LocalDate serviceDate) {
        // subwayPathSchedule returns both departures and arrivals. MODE=3/4 selects
        // the first/last journey, but its boarding boundary is always departureTime.
        JsonNode paths = node.path("result").path("path");
        if (!paths.isArray()) return null;
        for (JsonNode path : paths) {
            LocalDateTime departure = parseTime(path.path("info").path("departureTime").asText(null), serviceDate);
            if (departure == null) continue;
            // ODsay may encode after-midnight service as either 00:xx or 24:xx.
            if (!first && departure.toLocalDate().equals(serviceDate) && departure.getHour() < 3) {
                departure = departure.plusDays(1);
            }
            return departure;
        }
        return null;
    }
    private LocalDateTime parseTime(String v, LocalDate serviceDate) {
        try {
            if (v == null || v.isBlank() || serviceDate == null) return null;
            String x = v.replace(":", "");
            if (x.length() >= 4) x = x.substring(0, 4);
            int hour = Integer.parseInt(x.substring(0, 2));
            int minute = Integer.parseInt(x.substring(2, 4));
            if (hour < 0 || minute < 0 || minute >= 60) return null;
            int dayOffset = Math.floorDiv(hour, 24);
            int normalizedHour = Math.floorMod(hour, 24);
            return LocalDateTime.of(serviceDate.plusDays(dayOffset),
                    LocalTime.of(normalizedHour, minute));
        } catch (Exception e) { return null; }
    }
    private boolean same(JsonNode n, String value) { return value != null && !n.isMissingNode() && value.equals(n.asText()); }
    private String hash(String v, NotificationScheduleType type) {
        if (type != NotificationScheduleType.LAST_TRANSIT) return hash(v);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("last-departure-time-v2:" + hash(v)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    private String hash(String v) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(((SeoulBusScheduleService.isKakao(transitApiService.readSavedRoute(v)) ? "schedule-v6:" : "schedule-v4:") + (v==null?"":v)).getBytes(StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException(e);} }
    public record Decision(LocalDateTime scheduledAt, LocalDateTime hardDeadlineAt, DeliveryPhase phase, LocalDateTime baseDepartureAt, LocalDateTime effectiveDepartureAt, boolean recovery, int estimatedDuration) {}
    private record RouteSchedulePlan(LocalDateTime departure, int durationMinutes, String source,
                                     String providerDetails, int evaluationLeadMinutes) {}
    private record FirstBoundary(LocalDateTime firstDeparture,
                                 SeoulBusScheduleService.Schedule providerSchedule) {}
    private record RecoveryCandidate(LocalDateTime boardingAt, LocalDateTime scheduledAt) {}
}
