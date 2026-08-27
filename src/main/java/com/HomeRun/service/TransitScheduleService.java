package com.HomeRun.service;

import com.HomeRun.dto.TransitDto;
import com.HomeRun.entity.*;
import com.HomeRun.repository.ScheduleSnapshotRepository;
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
    private final TransitApiService transitApiService;
    private final PublicDataTransitService publicDataTransitService;
    private final ScheduleSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${odsay.api.key:}") private String apiKey;
    @Value("${odsay.schedule.url:https://api.odsay.com/v1/api}") private String scheduleBaseUrl;
    @Value("${odsay.api.referer:http://localhost:8080/}") private String odsayReferer;

    @Autowired
    public TransitScheduleService(TransitApiService transitApiService,
                                  PublicDataTransitService publicDataTransitService,
                                  ScheduleSnapshotRepository snapshotRepository,
                                  ObjectMapper objectMapper) {
        this(transitApiService, publicDataTransitService, snapshotRepository, objectMapper, new RestTemplate());
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

    @Transactional
    public Decision evaluate(ArrivalNotification notification, LocalDate date, LocalDateTime now, ZoneId zone) {
        NotificationScheduleType type = notification.getScheduleType();
        String details = notification.getRouteDetails();
        String hash = hash(details);
        Optional<ScheduleSnapshot> existing = snapshotRepository
                .findForUpdate(notification.getId(), date, type, hash);
        ScheduleSnapshot snapshot = existing.orElseGet(() -> createSnapshot(notification, date, type, hash, zone));
        if (snapshot.getEvaluationMode() == ScheduleEvaluationMode.FINISHED) return null;

        int offset = type == NotificationScheduleType.FIRST_TRANSIT
                ? notification.getReminderOffsetMinutesList().stream().max(Integer::compareTo).orElse(0)
                : notification.getReminderOffsetMinutes();
        LocalDateTime scheduled = snapshot.getEffectiveScheduledAt();
        LocalDateTime deadline = snapshot.getEffectiveDepartureAt();
        DeliveryPhase phase = DeliveryPhase.BASE;

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
        String hash = hash(notification.getRouteDetails());
        snapshotRepository.findForUpdate(notification.getId(), date, notification.getScheduleType(), hash)
                .ifPresent(snapshot -> { snapshot.markRecoveryDeliveryCreated(); snapshotRepository.save(snapshot); });
    }

    private ScheduleSnapshot createSnapshot(ArrivalNotification n, LocalDate date,
                                             NotificationScheduleType type, String hash, ZoneId zone) {
        TransitDto.RouteOptionResponse route = transitApiService.readSavedRoute(n.getRouteDetails());
        List<TransitDto.RouteSegment> segments = route.getSegments() == null ? List.of() : route.getSegments();
        LocalDateTime departure = date.atStartOfDay(zone).toLocalDateTime();
        List<LocalDateTime> candidates = new ArrayList<>();
            int prefix = 0;
        for (TransitDto.RouteSegment s : segments) {
            if ("BUS".equals(s.getTransitType()) || "SUBWAY".equals(s.getTransitType())) {
                LocalDateTime service = serviceTime(s, type, date);
                if (service != null) candidates.add(service.minusMinutes(prefix));
            }
            prefix += Math.max(0, s.getDurationMinutes() == null ? 0 : s.getDurationMinutes());
        }
        if (candidates.isEmpty()) throw new IllegalStateException("운행정보가 있는 transit segment가 없습니다.");
        departure = type == NotificationScheduleType.FIRST_TRANSIT
                ? candidates.stream().max(LocalDateTime::compareTo).orElseThrow()
                : candidates.stream().min(LocalDateTime::compareTo).orElseThrow();
        int offset = type == NotificationScheduleType.FIRST_TRANSIT
                ? n.getReminderOffsetMinutesList().stream().max(Integer::compareTo).orElse(0)
                : n.getReminderOffsetMinutes();
        LocalDateTime scheduled = departure.minusMinutes(offset);
        int duration = route.getTotalDurationMinutes() == null ? prefix : route.getTotalDurationMinutes();
        LocalDateTime start = scheduled.minusMinutes(Math.max(15, Math.min(60, duration)));
        return snapshotRepository.save(new ScheduleSnapshot(n, date, type, hash, departure, scheduled, start,
                LocalDateTime.now(ZoneOffset.UTC), duration));
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

    private LocalDateTime serviceTime(TransitDto.RouteSegment s, NotificationScheduleType type, LocalDate serviceDate) {
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
                    String field = type == NotificationScheduleType.FIRST_TRANSIT ? "busFirstTime" : "busLastTime";
                    serviceTime = parseTime(lane.path(field).asText(null), serviceDate);
                    break;
                }
            }
            log.debug("BUS schedule lookup: scheduleType={}, stationId={}, odsayRouteId={}, localRouteId={}, laneCount={}, routeMatched={}, serviceTimePresent={}",
                    type, s.getOdsayStartStationId(), s.getOdsayRouteId(), s.getLocalRouteId(), laneCount, matched, serviceTime != null);
            return serviceTime;
        }
        if ("SUBWAY".equals(s.getTransitType())) {
            JsonNode root = request("/subwayPathSchedule", Map.of(
                    "SID", s.getOdsayStartStationId(), "EID", s.getOdsayEndStationId(),
                    "MODE", type == NotificationScheduleType.FIRST_TRANSIT ? "3" : "4", "DAY", "1"));
            ensureNoOdsayError(root);
            return findTime(root, type == NotificationScheduleType.FIRST_TRANSIT, serviceDate);
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
        throw new IllegalStateException("ODsay schedule API error: code="
                + (code == null ? "unknown" : code)
                + ", message=" + (message == null ? "unknown" : message));
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
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) { throw new IllegalStateException("운행정보 조회 실패", e); }
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
        String[] names = first ? new String[]{"startTime","departureTime","firstTime"} : new String[]{"endTime","arrivalTime","lastTime"};
        if (node.isObject()) for (String name : names) { LocalDateTime t = parseTime(node.path(name).asText(null), serviceDate); if (t != null) return t; }
        if (node.isObject() || node.isArray()) for (JsonNode child : node) { LocalDateTime t = findTime(child, first, serviceDate); if (t != null) return t; }
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
    private String hash(String v) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((v==null?"":v).getBytes(StandardCharsets.UTF_8))); } catch(Exception e){throw new IllegalStateException(e);} }
    public record Decision(LocalDateTime scheduledAt, LocalDateTime hardDeadlineAt, DeliveryPhase phase, LocalDateTime baseDepartureAt, LocalDateTime effectiveDepartureAt, boolean recovery, int estimatedDuration) {}
    private record RecoveryCandidate(LocalDateTime boardingAt, LocalDateTime scheduledAt) {}
}
