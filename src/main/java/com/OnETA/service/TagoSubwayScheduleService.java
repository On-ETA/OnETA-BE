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
import java.time.*;
import java.util.*;

/** TAGO publishes station schedules, not train IDs. Results are conservative estimates. */
@Service
@Slf4j
public class TagoSubwayScheduleService {
    private final RestTemplate http;
    private final ObjectMapper mapper;
    private final String key;
    private final String url;
    private final Map<String, Cached> cache = new HashMap<>();

    @Autowired
    public TagoSubwayScheduleService(ObjectMapper mapper,
            @Value("${publicdata.api.key:}") String key,
            @Value("${publicdata.subway.url:https://apis.data.go.kr/1613000/SubwayInfo}") String url) {
        this(mapper, key, url, client());
    }
    TagoSubwayScheduleService(ObjectMapper mapper, String key, String url, RestTemplate http) {
        this.mapper = mapper; this.key = key.contains("%") ? URLDecoder.decode(key, StandardCharsets.UTF_8) : key.trim();
        this.url = url; this.http = http;
    }
    private static RestTemplate client() {
        var f = new SimpleClientHttpRequestFactory(); f.setConnectTimeout(3000); f.setReadTimeout(5000);
        return new RestTemplate(f);
    }

    public SeoulBusScheduleService.Schedule resolve(TransitDto.RouteSegment segment, LocalDate day) {
        return resolveWithDirectionInfo(segment, day).schedule();
    }

    record DirectionResult(SeoulBusScheduleService.Schedule schedule, boolean ambiguous) { }

    DirectionResult resolveWithDirectionInfo(TransitDto.RouteSegment segment, LocalDate day) {
        log.info("TAGO resolve: line={}, start={}, end={}, serviceDate={}",
                segment.getTransitName(), segment.getStartStation(), segment.getEndStation(), day);
        if (segment.getStations() == null || segment.getStations().size() < 2
                || segment.getDurationMinutes() == null || segment.getDurationMinutes() <= 0) throw unsupported();
        checkSeoul(segment.getStartX(), segment.getStartY()); checkSeoul(segment.getEndX(), segment.getEndY());
        String line = normalizeLine(segment.getTransitName());
        if (!line.matches("[1-9]호선")) throw unsupported();
        String start = station(segment.getStartStation(), line);
        String end = station(segment.getEndStation(), line);
        String next = station(segment.getStations().get(1).getName(), line);
        // Never mix weekday and public-holiday trains: they run on different timetables.
        List<String> days = List.of(KoreanSubwayServiceDay.tagoDay(day));
        LocalDateTime first = null, last = null;
        boolean ambiguous = false;
        for (String dayType : days) {
            List<LocalDateTime> firsts = new ArrayList<>(), lasts = new ArrayList<>();
            Map<String, LocalDateTime> lastByDirection = new LinkedHashMap<>();
            for (String direction : List.of("U", "D")) {
                List<LocalDateTime> valid = new ArrayList<>();
                var starts = timetable(start, dayType, direction);
                var nexts = timetable(next, dayType, direction);
                var ends = next.equals(end) ? nexts : timetable(end, dayType, direction);
                int invalidDeparture = 0, missingTerminal = 0, terminalIsStart = 0;
                int nextRejected = 0, endRejected = 0;
                for (JsonNode row : starts) {
                    LocalDateTime departure = time(field(row, "depTime"), day);
                    String terminal = field(row, "endSubwayStationId");
                    if (departure == null) { invalidDeparture++; continue; }
                    if (terminal.isBlank()) { missingTerminal++; continue; }
                    if (terminal.equals(start)) { terminalIsStart++; continue; }
                    // Match direction against the next stop and destination schedules.
                    // No numerical station-ID ordering or fabricated train ID is used.
                    boolean nextReachable = connects(nexts, terminal, departure, day, 10);
                    boolean endReachable = terminal.equals(end) || connects(ends, terminal, departure, day,
                            Math.max(10, segment.getDurationMinutes() * 2));
                    if (!nextReachable) nextRejected++;
                    if (!endReachable) endRejected++;
                    if (nextReachable && endReachable) valid.add(departure);
                }
                log.info("TAGO timetable filter: line={}, start={}, next={}, end={}, serviceDate={}, dayType={}, direction={}, "
                                + "startRows={}, nextRows={}, endRows={}, invalidDeparture={}, missingTerminal={}, terminalIsStart={}, "
                                + "nextParsedArrivals={}, endParsedArrivals={}, nextRejected={}, endRejected={}, valid={}",
                        line, start, next, end, day, dayType, direction, starts.size(), nexts.size(), ends.size(),
                        invalidDeparture, missingTerminal, terminalIsStart,
                        nexts.stream().filter(r -> time(field(r, "arrTime"), day) != null).count(),
                        ends.stream().filter(r -> time(field(r, "arrTime"), day) != null).count(),
                        nextRejected, endRejected, valid.size());
                if (!valid.isEmpty()) {
                    firsts.add(Collections.min(valid)); lasts.add(Collections.max(valid));
                    lastByDirection.put(direction, Collections.max(valid));
                }
            }
            if (firsts.isEmpty()) {
                log.info("TAGO dayType skipped: line={}, startName={}, endName={}, serviceDate={}, dayType={}; no valid direction",
                        line, segment.getStartStation(), segment.getEndStation(), day, dayType);
                continue;
            }
            // Without train IDs, ambiguous directions use the earlier boundary,
            // never the latest departure from the opposite direction.
            var f = Collections.min(firsts);
            var l = Collections.min(lasts);
            if (lastByDirection.size() > 1) {
                ambiguous = true;
                log.info("TAGO subway ambiguous directions: line={}, start={}, end={}, day={}, "
                                + "lastByDirection={}, selectedEarlierLast={}",
                        line, segment.getStartStation(), segment.getEndStation(), day,
                        lastByDirection, l);
            }
            if (first == null || f.isBefore(first)) first = f;
            if (last == null || l.isBefore(last)) last = l;
        }
        if (first == null || last == null) {
            log.error("TAGO T005: stage=TIMETABLE_FILTER, line={}, startName={}, endName={}, startId={}, nextId={}, endId={}, serviceDate={}, dayTypes={}; no valid direction in any dayType",
                    line, segment.getStartStation(), segment.getEndStation(), start, next, end, day, days);
            throw unsupported();
        }
        return new DirectionResult(
                new SeoulBusScheduleService.Schedule(start, "", "TAGO_SUBWAY:" + line, first, last, 0, end, 0),
                ambiguous);
    }

    private boolean connects(List<JsonNode> rows, String terminal, LocalDateTime departure, LocalDate day, int maxMinutes) {
        return rows.stream().anyMatch(r -> {
            if (!terminal.equals(field(r, "endSubwayStationId"))) return false;
            LocalDateTime arrival = time(field(r, "arrTime"), day);
            return arrival != null && arrival.isAfter(departure) && !arrival.isAfter(departure.plusMinutes(maxMinutes));
        });
    }
    private String station(String name, String line) {
        var rows = request("/GetKwrdFndSubwaySttnList", Map.of("subwayStationName", normalizeName(name)));
        var matches = rows.stream().filter(r -> normalizeName(field(r, "subwayStationName")).equals(normalizeName(name))
                && normalizeLine(field(r, "subwayRouteName")).equals(line)).toList();
        if (matches.size() != 1 || field(matches.get(0), "subwayStationId").isBlank()) {
            log.error("TAGO T005: stage=STATION_LINE_MAPPING, stationName={}, line={}, responseRows={}, nameMatches={}, lineMatches={}, matchedId={}",
                    name, line, rows.size(),
                    rows.stream().filter(r -> normalizeName(field(r, "subwayStationName")).equals(normalizeName(name))).count(),
                    matches.size(), matches.size() == 1 ? field(matches.get(0), "subwayStationId") : "");
            throw unsupported();
        }
        return field(matches.get(0), "subwayStationId");
    }
    private List<JsonNode> timetable(String station, String day, String direction) {
        return request("/GetSubwaySttnAcctoSchdulList", Map.of("subwayStationId", station,
                "dailyTypeCode", day, "upDownTypeCode", direction));
    }
    private synchronized List<JsonNode> request(String path, Map<String, String> params) {
        String cacheKey = path + new TreeMap<>(params);
        var cached = cache.get(cacheKey);
        if (cached != null && cached.until().isAfter(Instant.now())) return cached.rows();
        if (key.isBlank()) throw unavailable();
        List<JsonNode> rows = new ArrayList<>();
        try {
            for (int page = 1; page <= 10; page++) {
                var builder = UriComponentsBuilder.fromUriString(url + path).queryParam("serviceKey", "{key}")
                        .queryParam("_type", "json").queryParam("numOfRows", 500).queryParam("pageNo", page);
                params.forEach(builder::queryParam);
                ExternalApiCallCounter.record("TAGO_SUBWAY", path);
                var root = mapper.readTree(http.getForObject(builder.encode().buildAndExpand(key).toUri(), String.class))
                        .path("response");
                if (!"00".equals(root.path("header").path("resultCode").asText())) throw unavailable();
                var body = root.path("body"); var items = body.path("items").path("item");
                if (items.isArray()) items.forEach(rows::add);
                else if (items.isObject()) rows.add(items);
                if (rows.size() >= body.path("totalCount").asInt()) {
                    if (cache.size() >= 2000) cache.clear();
                    cache.put(cacheKey, new Cached(List.copyOf(rows), Instant.now().plusSeconds(3600)));
                    return rows;
                }
            }
            throw unavailable();
        } catch (Exception e) {
            log.warn("TAGO subway lookup failed: endpoint={}, failureType={}", path, e.getClass().getSimpleName());
            throw unavailable();
        }
    }
    static LocalDateTime time(String value, LocalDate day) {
        try {
            if (value == null || value.equals("0") || value.isBlank()) return null;
            String digits = value.replace(":", "");
            if (digits.length() < 6) digits = "0".repeat(6 - digits.length()) + digits;
            int hour = Integer.parseInt(digits.substring(0, 2));
            if (hour < 3) hour += 24;
            if (hour > 29) return null;
            return day.plusDays(hour / 24).atTime(hour % 24, Integer.parseInt(digits.substring(2, 4)),
                    Integer.parseInt(digits.substring(4, 6)));
        } catch (RuntimeException e) { return null; }
    }
    private static String field(JsonNode row, String name) {
        return row.path(name).asText(row.path(name.toLowerCase(Locale.ROOT)).asText(""));
    }
    private static String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\([^)]*\\)", "").replaceAll("\\s", "");
    }
    private static String normalizeLine(String line) {
        return line == null ? "" : line.replace("수도권", "").replace("서울", "").replaceAll("\\s", "");
    }
    private static void checkSeoul(Double x, Double y) {
        if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)
                || x < 126.7 || x > 127.3 || y < 37.4 || y > 37.75) throw unsupported();
    }
    private static GlobalException unsupported() { return new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED); }
    private static GlobalException unavailable() { return new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE); }
    private record Cached(List<JsonNode> rows, Instant until) { }
}
