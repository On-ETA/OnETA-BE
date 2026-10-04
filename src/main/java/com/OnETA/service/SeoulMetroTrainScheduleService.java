package com.OnETA.service;

import com.OnETA.common.ExternalApiCallCounter;
import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * Seoul Metro train timetable fallback for Kakao subway segments.
 *
 * Uses the approved data.go.kr Seoul Metro train schedule API. Direction is
 * verified by matching the same train number at both the start and end
 * stations instead of guessing from station names.
 */
@Service
@Slf4j
public class SeoulMetroTrainScheduleService {
    private static final String DEFAULT_URL =
            "https://apis.data.go.kr/B553766/schedule/getTrainSch";
    private static final Set<String> SUCCESS_CODES = Set.of("", "00", "INFO-000", "03", "NODATA_ERROR");

    private final ObjectMapper mapper;
    private final RestTemplate http;
    private final String key;
    private final String url;
    private final Map<String, Cached> cache = new HashMap<>();

    @Autowired
    public SeoulMetroTrainScheduleService(
            ObjectMapper mapper,
            @Value("${publicdata.api.key:${PUBLICDATA_API_KEY:}}") String key,
            @Value("${publicdata.seoul-metro.schedule-url:" + DEFAULT_URL + "}") String url) {
        this(mapper, createHttp(), key, url);
    }

    SeoulMetroTrainScheduleService(ObjectMapper mapper, RestTemplate http, String key, String url) {
        this.mapper = mapper;
        this.http = http;
        String trimmed = key == null ? "" : key.trim();
        this.key = trimmed.contains("%")
                ? URLDecoder.decode(trimmed, StandardCharsets.UTF_8)
                : trimmed;
        this.url = (url == null || url.isBlank()) ? DEFAULT_URL : url.trim();
    }

    private static RestTemplate createHttp() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(7000);
        return new RestTemplate(factory);
    }

    public synchronized SeoulBusScheduleService.Schedule resolve(
            TransitDto.RouteSegment segment, LocalDate serviceDate) {
        validate(segment, serviceDate);
        String cacheKey = serviceDate + "|" + normalize(segment.getTransitName()) + "|"
                + normalize(segment.getStartStation()) + "|" + normalize(segment.getEndStation());
        Cached cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(java.time.Instant.now())) {
            return cached.schedule();
        }
        if (key.isBlank()) throw unavailable();

        String weekday = isWeekend(serviceDate) ? "주말" : "평일";
        List<String> directions = directions(segment.getTransitName());
        List<Trip> validTrips = new ArrayList<>();

        for (String direction : directions) {
            List<Row> starts = fetch(segment.getTransitName(), segment.getStartStation(),
                    direction, weekday, serviceDate);
            List<Row> ends = fetch(segment.getTransitName(), segment.getEndStation(),
                    direction, weekday, serviceDate);
            validTrips.addAll(matchTrips(starts, ends, segment, serviceDate));
        }

        if (validTrips.isEmpty()) {
            log.info("Seoul Metro schedule had no connectable train: line={}, start={}, end={}, serviceDate={}",
                    segment.getTransitName(), segment.getStartStation(), segment.getEndStation(), serviceDate);
            throw unsupported();
        }

        validTrips.sort(Comparator.comparing(Trip::departure));
        LocalDateTime first = validTrips.get(0).departure();
        LocalDateTime last = validTrips.get(validTrips.size() - 1).departure();
        var schedule = new SeoulBusScheduleService.Schedule(
                normalize(segment.getStartStation()), "", "SEOUL_METRO:" + normalize(segment.getTransitName()),
                first, last, 0, normalize(segment.getEndStation()), 0);

        if (cache.size() >= 1000) cache.clear();
        cache.put(cacheKey, new Cached(schedule, java.time.Instant.now().plus(Duration.ofHours(6))));
        return schedule;
    }

    private List<Row> fetch(String lineName, String stationName, String direction,
                            String weekday, LocalDate serviceDate) {
        try {
            var builder = UriComponentsBuilder.fromUriString(url)
                    .queryParam("serviceKey", "{serviceKey}")
                    .queryParam("dataType", "JSON")
                    .queryParam("tmprTmtblYn", "N")
                    .queryParam("upbdnbSe", direction)
                    .queryParam("wkndSe", weekday)
                    .queryParam("lineNm", lineName)
                    .queryParam("stnNm", stationName)
                    .queryParam("searchDt", serviceDate + "T00:00:00")
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "1000");
            var uri = builder.encode().buildAndExpand(key).toUri();

            ExternalApiCallCounter.record("SEOUL_METRO", "train-schedule");
            String body = http.getForObject(uri, String.class);
            JsonNode root = mapper.readTree(body);
            String resultCode = root.path("response").path("header").path("resultCode").asText("");
            if (!SUCCESS_CODES.contains(resultCode)) {
                log.warn("Seoul Metro schedule API rejected request: resultCode={}", resultCode);
                throw unavailable();
            }
            if ("03".equals(resultCode) || "NODATA_ERROR".equals(resultCode)) return List.of();

            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isMissingNode() || items.isNull()) return List.of();

            List<Row> rows = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) parseRow(item, direction).ifPresent(rows::add);
            } else if (items.isObject()) {
                parseRow(items, direction).ifPresent(rows::add);
            }
            return rows;
        } catch (GlobalException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Seoul Metro schedule lookup failed: line={}, station={}, direction={}, failureType={}",
                    lineName, stationName, direction, e.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private Optional<Row> parseRow(JsonNode item, String direction) {
        String trainNo = text(item, "trainno", "trainNo");
        String departure = text(item, "trainDptreTm", "dptreTm");
        String arrival = text(item, "trainArvlTm", "arvlTm");
        String rowDirection = text(item, "upbdnbSe");
        if (trainNo.isBlank() || (departure.isBlank() && arrival.isBlank())) return Optional.empty();
        if (!rowDirection.isBlank() && !normalize(rowDirection).equals(normalize(direction))) {
            return Optional.empty();
        }
        return Optional.of(new Row(trainNo, departure, arrival));
    }

    private List<Trip> matchTrips(List<Row> starts, List<Row> ends,
                                  TransitDto.RouteSegment segment, LocalDate serviceDate) {
        Map<String, List<Row>> endsByTrain = new HashMap<>();
        for (Row end : ends) {
            endsByTrain.computeIfAbsent(end.trainNo(), ignored -> new ArrayList<>()).add(end);
        }

        int expected = Math.max(1, segment.getDurationMinutes() == null ? 1 : segment.getDurationMinutes());
        long maxTravelMinutes = Math.max(25, expected + 25L);
        List<Trip> result = new ArrayList<>();

        for (Row start : starts) {
            LocalDateTime departure = scheduleTime(
                    start.departure().isBlank() ? start.arrival() : start.departure(), serviceDate);
            if (departure == null) continue;
            for (Row end : endsByTrain.getOrDefault(start.trainNo(), List.of())) {
                LocalDateTime arrival = scheduleTime(
                        end.arrival().isBlank() ? end.departure() : end.arrival(), serviceDate);
                if (arrival == null) continue;
                while (!arrival.isAfter(departure) && arrival.isBefore(serviceDate.plusDays(2).atStartOfDay())) {
                    arrival = arrival.plusDays(1);
                }
                long minutes = Duration.between(departure, arrival).toMinutes();
                if (minutes <= 0 || minutes > maxTravelMinutes) continue;
                result.add(new Trip(start.trainNo(), departure, arrival));
            }
        }
        return result;
    }

    static LocalDateTime scheduleTime(String raw, LocalDate serviceDate) {
        if (raw == null || raw.isBlank() || serviceDate == null) return null;
        try {
            String digits = raw.trim().replace(":", "");
            if (digits.length() < 4) return null;
            int hour = Integer.parseInt(digits.substring(0, 2));
            int minute = Integer.parseInt(digits.substring(2, 4));
            int second = digits.length() >= 6 ? Integer.parseInt(digits.substring(4, 6)) : 0;
            if (minute > 59 || second > 59 || hour < 0) return null;
            int dayOffset = hour / 24;
            int normalizedHour = hour % 24;
            // Timetables commonly express after-midnight final service as 00:xx
            // rather than 24:xx. Treat it as the tail of the service day.
            if (dayOffset == 0 && normalizedHour < 4) dayOffset = 1;
            return LocalDateTime.of(serviceDate.plusDays(dayOffset),
                    LocalTime.of(normalizedHour, minute, second));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<String> directions(String lineName) {
        String line = normalize(lineName);
        if ("2호선".equals(line)) return List.of("내선", "외선");
        return List.of("상행", "하행");
    }

    private boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
    }

    private void validate(TransitDto.RouteSegment segment, LocalDate serviceDate) {
        if (segment == null || serviceDate == null
                || !"SUBWAY".equals(segment.getTransitType())
                || normalize(segment.getTransitName()).isBlank()
                || normalize(segment.getStartStation()).isBlank()
                || normalize(segment.getEndStation()).isBlank()) {
            throw unsupported();
        }
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\([^)]*\\)", "")
                .replaceAll("\\s", "").trim();
    }

    private static GlobalException unsupported() {
        return new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED);
    }

    private static GlobalException unavailable() {
        return new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE);
    }

    private record Row(String trainNo, String departure, String arrival) {}
    private record Trip(String trainNo, LocalDateTime departure, LocalDateTime arrival) {}
    private record Cached(SeoulBusScheduleService.Schedule schedule, java.time.Instant expiresAt) {}
}
