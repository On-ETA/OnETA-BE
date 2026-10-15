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
                            || segment.isNightBus() || Tr…15720 tokens truncated…plan.departure(), DeliveryPhase.BASE, snapshot.getBaseDepartureAt(),
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
