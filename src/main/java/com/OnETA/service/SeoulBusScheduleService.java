package com.OnETA.service;

import com.OnETA.common.ExternalApiCallCounter;
import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Seoul station-specific, current-service-day schedules for one bus ride only. */
@Service
@Slf4j
public class SeoulBusScheduleService {
    private final RestTemplate http;
    private final String key;
    private final String baseUrl;
    private final Clock clock;
    private Instant retryAfter = Instant.EPOCH;
    private final Map<String, Cached> cache = new HashMap<>();
    private final Map<String, LiveCache> liveCache = new HashMap<>();
    private TagoSubwayScheduleService subway;

    @Autowired
    void setSubway(TagoSubwayScheduleService subway) { this.subway = subway; }

    @Autowired
    public SeoulBusScheduleService(@Value("${publicdata.seoul.schedule-key:${publicdata.api.key:}}") String key,
                                   @Value("${publicdata.seoul.url:http://ws.bus.go.kr}") String baseUrl) {
        this(createHttp(), key, baseUrl, Clock.system(ZoneId.of("Asia/Seoul")));
    }

    SeoulBusScheduleService(RestTemplate http, String key, String baseUrl, Clock clock) {
        this.http = http;
        String trimmedKey = key == null ? "" : key.trim();
        this.key = trimmedKey.contains("%") ? URLDecoder.decode(trimmedKey, StandardCharsets.UTF_8) : trimmedKey;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.clock = clock;
    }

    private static RestTemplate createHttp() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    public static boolean isKakao(TransitDto.RouteOptionResponse route) {
        return route != null && ("KAKAO".equals(route.getProvider())
                || (route.getRouteId() != null && route.getRouteId().startsWith("KAKAO_")));
    }

    public LocalDate today() { return LocalDate.now(clock); }

    public List<Schedule> resolveRoute(TransitDto.RouteOptionResponse route, LocalDate day) {
        validateRoute(route);
        return route.getSegments().stream().filter(s -> !"WALK".equals(s.getTransitType()))
                .map(s -> "SUBWAY".equals(s.getTransitType()) ? subway.resolve(s, day)
                        : resolve(route.toBuilder().transferCount(0).segments(List.of(s)).build(), day)).toList();
    }

    private void validateRoute(TransitDto.RouteOptionResponse route) {
        if (!isKakao(route) || route.getSegments() == null || route.getSegments().isEmpty()
                || route.getSegments().size() > 30 || route.getTotalDurationMinutes() == null
                || route.getTotalDurationMinutes() <= 0) throw unsupported();
        int buses = 0;
        for (var s : route.getSegments()) {
            if (s == null || s.getDurationMinutes() == null || s.getDurationMinutes() < 0
                    || s.getDurationMinutes() > 1440) throw unsupported();
            if ("BUS".equals(s.getTransitType()) || "SUBWAY".equals(s.getTransitType())) buses++;
            else if (!"WALK".equals(s.getTransitType())) throw unsupported();
        }
        if (buses < 1 || buses > 5) throw unsupported();
    }

    public synchronized Schedule resolve(TransitDto.RouteOptionResponse route, LocalDate day) {
        var bus = singleBus(route);
        if (!today().equals(day)) throw unavailable(); // API has no service-date parameter.
        String cacheKey = day + ":" + bus.getTransitName() + ":" + bus.getStartStation() + ":"
                + bus.getEndStation() + ":" + bus.getStartX() + ":" + bus.getStartY() + ":"
                + bus.getEndX() + ":" + bus.getEndY() + ":"
                + bus.getStations().stream().map(TransitDto.RouteStation::getName).toList();
        Cached cached = cache.get(cacheKey);
        if (cached != null && cached.expires().isAfter(clock.instant())) return cached.schedule();
        if (key.isBlank() || retryAfter.isAfter(clock.instant())) throw unavailable();

        List<Element> starts = nearby(bus.getStartStation(), bus.getStartX(), bus.getStartY());
        List<Element> ends = nearby(bus.getEndStation(), bus.getEndX(), bus.getEndY());
        List<Binding> matches = new ArrayList<>();
        for (Element start : starts) {
            String ars = text(start, "arsId");
            List<Element> routes = request("/stationinfo/getRouteByStation", Map.of("arsId", ars), "stations");
            for (Element candidate : routes) {
                if (!normalize(text(candidate, "busRouteNm")).equals(normalize(bus.getTransitName()))
                        || !Set.of("2", "3", "4", "5", "6").contains(text(candidate, "busRouteType"))) continue;
                String routeId = text(candidate, "busRouteId");
                if (!routeId.matches("[0-9]{9}")) throw unsupported();
                List<Element> stops = request("/busRouteInfo/getStaionByRoute", Map.of("busRouteId", routeId), "route-stations");
                stops.sort(Comparator.comparingInt(this::sequence));
                for (int i = 0; i < stops.size(); i++) {
                    if (!text(stops.get(i), "station").equals(text(start, "stationId"))) continue;
                    for (Element end : ends) {
                        int last = i + bus.getStations().size() - 1;
                        if (last >= stops.size() || !text(stops.get(last), "station").equals(text(end, "stationId"))) continue;
                        boolean same = true;
                        for (int j = i; j <= last; j++) {
                            if (!normalize(text(stops.get(j), "stationNm"))
                                    .equals(normalize(bus.getStations().get(j - i).getName()))
                                    || (j > i && sequence(stops.get(j)) != sequence(stops.get(j - 1)) + 1)
                                    || (j > i && j < last && "Y".equals(text(stops.get(j), "transYn")))) same = false;
                        }
                        if (same) matches.add(new Binding(text(start, "stationId"), ars, routeId,
                                sequence(stops.get(i)), text(end, "stationId"), sequence(stops.get(last))));
                    }
                }
            }
        }
        if (matches.size() != 1) throw unsupported();
        Binding binding = matches.get(0);
        List<Element> times = request("/stationinfo/getBustimeByStation",
                Map.of("arsId", binding.arsId(), "busRouteId", binding.routeId()), "stations").stream()
                .filter(e -> binding.arsId().equals(text(e, "arsId"))
                        && binding.routeId().equals(text(e, "busRouteId"))).toList();
        if (times.size() != 1) throw unsupported();
        LocalDateTime first = parseTime(text(times.get(0), "firstBusTm"), day);
        LocalDateTime last = parseTime(text(times.get(0), "lastBusTm"), day);
        if (last.isBefore(first) && last.toLocalDate().equals(day)) last = last.plusDays(1);
        if (!first.toLocalDate().equals(day) || !last.isAfter(first)
                || last.isAfter(day.plusDays(1).atTime(12, 0))) throw unsupported();
        Schedule schedule = new Schedule(binding.stationId(), binding.arsId(), binding.routeId(), first, last,
                binding.order(), binding.endStationId(), binding.endOrder());
        if (cache.size() >= 1000) cache.clear();
        cache.put(cacheKey, new Cached(schedule, clock.instant().plus(Duration.ofMinutes(30))));
        return schedule;
    }

    /** Route-wide arrivals include vehicle IDs, current sections and predictions at both stops. */
    public synchronized List<LiveBus> arrivals(Schedule schedule, LocalDateTime now) {
        if (schedule.order() <= 0) return List.of();
        LiveCache cached = liveCache.get(schedule.routeId());
        if (cached == null || !cached.expires().isAfter(clock.instant())) {
            if (key.isBlank() || retryAfter.isAfter(clock.instant())) throw unavailable();
            List<Element> items = request("/arrive/getArrInfoByRouteAll",
                    Map.of("busRouteId", schedule.routeId()), "arrival");
            cached = new LiveCache(items, clock.instant().plusSeconds(20));
            if (liveCache.size() >= 1000) liveCache.clear();
            liveCache.put(schedule.routeId(), cached);
        }
        List<LiveBus> result = new ArrayList<>();
        for (Element item : cached.items()) {
            if (!schedule.stationId().equals(text(item, "stId"))
                    || !Integer.toString(schedule.order()).equals(text(item, "staOrd"))) continue;
            LocalDateTime observed;
            try { observed = LocalDateTime.parse(text(item, "mkTm").replace(' ', 'T')); }
            catch (RuntimeException e) { continue; }
            if (observed.isBefore(now.minusSeconds(90)) || observed.isAfter(now.plusSeconds(30))) continue;
            for (int n = 1; n <= 2; n++) {
                String vehicle = text(item, "vehId" + n);
                int seconds = positive(text(item, "exps" + n));
                if (vehicle.isBlank() || "0".equals(vehicle) || seconds <= 0
                        || "1".equals(text(item, "full" + n))) continue;
                LocalDateTime boarding = observed.plusSeconds(seconds);
                if (!boarding.isAfter(now)) continue;
                LocalDateTime alighting = null;
                for (Element end : cached.items()) {
                    if (!schedule.endStationId().equals(text(end, "stId"))
                            || !Integer.toString(schedule.endOrder()).equals(text(end, "staOrd"))) continue;
                    for (int m = 1; m <= 2; m++) {
                        if (!vehicle.equals(text(end, "vehId" + m))) continue;
                        try {
                            LocalDateTime endObserved = LocalDateTime.parse(text(end, "mkTm").replace(' ', 'T'));
                            int endSeconds = positive(text(end, "exps" + m));
                            LocalDateTime predicted = endObserved.plusSeconds(endSeconds);
                            if (endSeconds > 0 && !endObserved.isBefore(now.minusSeconds(90))
                                    && !endObserved.isAfter(now.plusSeconds(30)) && predicted.isAfter(boarding)) alighting = predicted;
                        } catch (RuntimeException ignored) { }
                    }
                }
                result.add(new LiveBus(boarding, alighting, "1".equals(text(item, "isLast" + n)), vehicle));
            }
        }
        return result.stream().sorted(Comparator.comparing(LiveBus::boarding)).toList();
    }

    private static int positive(String value) {
        try { return Math.max(0, Integer.parseInt(value)); } catch (RuntimeException e) { return 0; }
    }

    private TransitDto.RouteSegment singleBus(TransitDto.RouteOptionResponse route) {
        if (!isKakao(route) || route.getSegments() == null || route.getSegments().isEmpty()
                || (route.getTransferCount() != null && route.getTransferCount() != 0)
                || route.getTotalDurationMinutes() == null || route.getTotalDurationMinutes() <= 0) throw unsupported();
        var rides = route.getSegments().stream().filter(s -> !"WALK".equals(s.getTransitType())).toList();
        if (rides.size() != 1 || !"BUS".equals(rides.get(0).getTransitType())) throw unsupported();
        var bus = rides.get(0);
        if (bus.getStations() == null || bus.getStations().size() < 2 || normalize(bus.getTransitName()).isBlank()
                || !normalize(bus.getStartStation()).equals(normalize(bus.getStations().get(0).getName()))
                || !normalize(bus.getEndStation()).equals(normalize(bus.getStations().get(bus.getStations().size()-1).getName()))) throw unsupported();
        for (var segment : route.getSegments()) {
            if (segment.getDurationMinutes() == null || segment.getDurationMinutes() < 0) throw unsupported();
        }
        return bus;
    }

    private List<Element> nearby(String name, Double x, Double y) {
        if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)
                || x < 126.7 || x > 127.3 || y < 37.4 || y > 37.75) throw unsupported();
        var result = request("/stationinfo/getStationByPos",
                Map.of("tmX", x.toString(), "tmY", y.toString(), "radius", "100"), "stations").stream()
                .filter(e -> normalize(text(e, "stationNm")).equals(normalize(name)))
                .filter(e -> text(e, "arsId").matches("[0-9]{5}") && !"00000".equals(text(e, "arsId")))
                .filter(e -> text(e, "stationId").matches("[0-9]{9}"))
                .filter(e -> distance(x, y, text(e, "gpsX"), text(e, "gpsY")) <= 100).toList();
        if (result.isEmpty() || result.size() > 4) throw unsupported();
        return result;
    }

    private List<Element> request(String path, Map<String, String> params, String group) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl + "/api/rest" + path).queryParam("serviceKey", "{serviceKey}");
        params.forEach(builder::queryParam);
        try {
            ExternalApiCallCounter.record("SEOUL_BUS", group);
            byte[] body = http.getForObject(builder.encode().buildAndExpand(key).toUri(), byte[].class);
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            String code = text(document.getDocumentElement(), "headerCd");
            if ("4".equals(code)) return new ArrayList<>();
            if (!"0".equals(code)) {
                log.warn("Seoul API rejected request: endpoint={}, headerCode={}", path,
                        code.matches("[0-9]{1,4}") ? code : "unknown");
                throw unavailable();
            }
            var nodes = document.getElementsByTagName("itemList");
            List<Element> items = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) items.add((Element) nodes.item(i));
            return items;
        } catch (Exception e) {
            log.warn("Seoul API lookup failed: endpoint={}, failureType={}, httpStatus={}", path,
                    e.getClass().getSimpleName(), e instanceof RestClientResponseException response
                            ? response.getStatusCode().value() : null);
            retryAfter = clock.instant().plusSeconds(60);
            throw unavailable(); // Never expose URI containing the service key.
        }
    }

    static LocalDateTime parseTime(String value, LocalDate day) {
        try {
            String digits = value.replaceAll("[- :T]", "");
            if (digits.matches("[0-9]{14}")) return parseTime(digits.substring(8),
                    LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE));
            if (!digits.matches("[0-9]{4}([0-9]{2})?")) throw unsupported();
            int hour = Integer.parseInt(digits.substring(0, 2));
            int minute = Integer.parseInt(digits.substring(2, 4));
            int second = digits.length() == 6 ? Integer.parseInt(digits.substring(4, 6)) : 0;
            if (hour > 35) throw unsupported();
            return day.plusDays(hour / 24).atTime(hour % 24, minute, second);
        } catch (Exception e) { throw unsupported(); }
    }

    private static String text(Element e, String name) {
        var nodes = e.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
    private int sequence(Element e) {
        try {
            int value = Integer.parseInt(text(e, "seq"));
            if (value < 1) throw unsupported();
            return value;
        } catch (NumberFormatException ex) { throw unsupported(); }
    }
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\((?:[0-9]+번승강장|중)\\)", "").replaceAll("[\\s.·]", "");
    }
    private static double distance(double x, double y, String sx, String sy) {
        try {
            double lon = Double.parseDouble(sx), lat = Double.parseDouble(sy);
            double a = Math.pow(Math.sin(Math.toRadians(lat - y) / 2), 2)
                    + Math.cos(Math.toRadians(y)) * Math.cos(Math.toRadians(lat))
                    * Math.pow(Math.sin(Math.toRadians(lon - x) / 2), 2);
            return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        } catch (Exception e) { return Double.POSITIVE_INFINITY; }
    }
    private static GlobalException unsupported() { return new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNSUPPORTED); }
    private static GlobalException unavailable() { return new GlobalException(ErrorCode.TRANSIT_SCHEDULE_UNAVAILABLE); }
    private record Binding(String stationId, String arsId, String routeId, int order, String endStationId, int endOrder) { }
    private record Cached(Schedule schedule, Instant expires) { }
    private record LiveCache(List<Element> items, Instant expires) { }
    public record LiveBus(LocalDateTime boarding, LocalDateTime alighting, boolean last, String vehicleId) { }
    public record Schedule(String stationId, String arsId, String routeId, LocalDateTime first, LocalDateTime last,
                           int order, String endStationId, int endOrder) {
        public Schedule(String stationId, String arsId, String routeId, LocalDateTime first, LocalDateTime last) {
            this(stationId, arsId, routeId, first, last, 0, "", 0);
        }
    }
}
