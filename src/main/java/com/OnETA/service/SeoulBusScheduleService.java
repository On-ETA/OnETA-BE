package com.OnETA.service;

import com.OnETA.common.ExternalApiCallCounter;
import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.BusType;
import com.OnETA.dto.TransitDto;
import com.OnETA.entity.SeoulBusRoute;
import com.OnETA.repository.SeoulBusRouteRepository;
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
    private final SeoulBusRouteRepository seoulBusRouteRepository;
    private static final int NIGHT_ORIGIN_RADIUS_METERS = 1000;
    private static final int NIGHT_DESTINATION_RADIUS_METERS = 1200;
    private static final int MAX_DIRECT_NIGHT_ROUTES = 14;
    private static final Duration NIGHT_ROUTE_LIST_TTL = Duration.ofMinutes(15);
    private static final Duration ROUTE_LIST_BACKOFF = Duration.ofSeconds(60);

    private Instant retryAfter = Instant.EPOCH;
    private Instant routeListRetryAfter = Instant.EPOCH;
    private CachedNightRouteList cachedNightRouteList;
    private final Map<String, Cached> cache = new HashMap<>();
    private final Map<String, LiveCache> liveCache = new HashMap<>();
    private final Map<String, CachedRouteStops> routeStopsCache = new HashMap<>();
    private TagoSubwayScheduleService subway;

    @Autowired
    void setSubway(TagoSubwayScheduleService subway) { this.subway = subway; }

    @Autowired
    public SeoulBusScheduleService(@Value("${publicdata.seoul.schedule-key:${publicdata.api.key:}}") String key,
                                   @Value("${publicdata.seoul.url:http://ws.bus.go.kr}") String baseUrl,
                                   SeoulBusRouteRepository seoulBusRouteRepository) {
        this(createHttp(), key, baseUrl, Clock.system(ZoneId.of("Asia/Seoul")), seoulBusRouteRepository);
    }

    SeoulBusScheduleService(RestTemplate http, String key, String baseUrl, Clock clock) {
        this(http, key, baseUrl, clock, null);
    }

    SeoulBusScheduleService(RestTemplate http, String key, String baseUrl, Clock clock,
                            SeoulBusRouteRepository seoulBusRouteRepository) {
        this.http = http;
        String trimmedKey = key == null ? "" : key.trim();
        this.key = trimmedKey.contains("%") ? URLDecoder.decode(trimmedKey, StandardCharsets.UTF_8) : trimmedKey;
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.clock = clock;
        this.seoulBusRouteRepository = seoulBusRouteRepository;
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

    public static boolean usesSeoulBusSchedules(TransitDto.RouteOptionResponse route) {
        return isKakao(route) || (route != null && "SEOUL_NIGHT".equals(route.getProvider()));
    }

    public LocalDate today() { return LocalDate.now(clock); }

    /**
     * Finds a direct Seoul night-bus route independently of daytime route-planner rankings.
     * This is used only as a NIGHT_ONLY fallback for FIRST/LAST route discovery.
     */
    public Optional<TransitDto.RouteOptionResponse> discoverDirectNightRoute(
            double originX, double originY, double destX, double destY) {
        return discoverDirectNightRoutes(originX, originY, destX, destY).stream().findFirst();
    }

    /**
     * Keep one best stop-pair per N-bus route, rather than only the globally
     * shortest path: the shortest line may already have finished its last run.
     */
    public List<TransitDto.RouteOptionResponse> discoverDirectNightRoutes(
            double originX, double originY, double destX, double destY) {
        if (key.isBlank()) {
            log.info("Direct night-bus discovery skipped: Seoul API key not configured");
            return List.of();
        }
        if (retryAfter.isAfter(clock.instant())) {
            log.info("Direct night-bus discovery skipped: Seoul API retry backoff active");
            return List.of();
        }

        try {
            List<Element> originStops =
                    nearbyByPosition(originX, originY, NIGHT_ORIGIN_RADIUS_METERS);
            if (originStops.isEmpty()) {
                log.info("Direct night-bus discovery found no origin stops");
                return List.of();
            }

            Map<String, Element> originByStationId = new HashMap<>();
            for (Element originStop : originStops) {
                String stationId = stationIdOf(originStop);
                if (!stationId.isBlank()) originByStationId.put(stationId, originStop);
            }

            List<RouteSeed> nightRoutes = routeCandidates("N").stream()
                    .filter(route -> TransitRouteClassifier.isNightBusName(route.routeName()))
                    .toList();

            List<NightRouteCandidate> matches = new ArrayList<>();
            int failedRouteLookups = 0;
            for (RouteSeed route : nightRoutes) {
                List<Element> stops;
                try {
                    stops = routeStops(route.routeId(), "night-route-stations");
                } catch (GlobalException e) {
                    failedRouteLookups++;
                    log.warn("Direct night-bus line skipped: route={}, routeId={}, errorCode={}",
                            route.routeName(), route.routeId(), e.getErrorCode().getCode());
                    continue;
                } catch (RuntimeException e) {
                    failedRouteLookups++;
                    log.warn("Direct night-bus line skipped: route={}, routeId={}, errorType={}",
                            route.routeName(), route.routeId(), e.getClass().getSimpleName());
                    continue;
                }
                for (int startIndex = 0; startIndex < stops.size(); startIndex++) {
                    Element originStop = originByStationId.get(stationIdOf(stops.get(startIndex)));
                    if (originStop == null) continue;

                    double originWalkMeters = distance(
                            originX, originY,
                            stationXOf(originStop), stationYOf(originStop));
                    if (!Double.isFinite(originWalkMeters)
                            || originWalkMeters > NIGHT_ORIGIN_RADIUS_METERS) {
                        continue;
                    }

                    for (int endIndex = startIndex + 1; endIndex < stops.size(); endIndex++) {
                        Element destinationStop = stops.get(endIndex);
                        double destinationWalkMeters = distance(
                                destX, destY,
                                stationXOf(destinationStop), stationYOf(destinationStop));
                        if (!Double.isFinite(destinationWalkMeters)
                                || destinationWalkMeters > NIGHT_DESTINATION_RADIUS_METERS) {
                            continue;
                        }

                        int riddenStops = endIndex - startIndex;
                        double score = originWalkMeters
                                + destinationWalkMeters
                                + (riddenStops * 40.0);

                        matches.add(new NightRouteCandidate(
                                route.routeId(), route.routeName(), originStop, destinationStop,
                                stops, startIndex, endIndex, score,
                                (int) Math.ceil(originWalkMeters / 60.0),
                                (int) Math.ceil(destinationWalkMeters / 60.0)));
                    }
                }
            }

            Map<String, NightRouteCandidate> bestPerLine = new LinkedHashMap<>();
            for (NightRouteCandidate match : matches) {
                bestPerLine.merge(match.routeId(), match,
                        (left, right) -> left.score() <= right.score() ? left : right);
            }
            List<TransitDto.RouteOptionResponse> selected = bestPerLine.values().stream()
                    .sorted(Comparator.comparingDouble(NightRouteCandidate::score))
                    .limit(MAX_DIRECT_NIGHT_ROUTES)
                    .map(this::toNightRoute)
                    .toList();

            log.info("Direct night-bus discovery: originStops={}, nightRoutesSeen={}, failedRouteLookups={}, matches={}, selectedLines={}",
                    originStops.size(), nightRoutes.size(), failedRouteLookups, matches.size(),
                    selected.stream()
                            .flatMap(route -> route.getSegments().stream())
                            .filter(segment -> "BUS".equals(segment.getTransitType()))
                            .map(TransitDto.RouteSegment::getTransitName).toList());
            return selected;
        } catch (GlobalException e) {
            log.info("Direct night-bus discovery unavailable: {}", e.getErrorCode().getCode());
            return List.of();
        } catch (RuntimeException e) {
            log.warn("Direct night-bus discovery failed: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    public List<Schedule> resolveRoute(TransitDto.RouteOptionResponse route, LocalDate day) {
        validateRoute(route);
        return route.getSegments().stream().filter(s -> !"WALK".equals(s.getTransitType()))
                .map(s -> "SUBWAY".equals(s.getTransitType()) ? subway.resolve(s, day)
                        : resolve(route.toBuilder().transferCount(0).segments(List.of(s)).build(), day)).toList();
    }

    private void validateRoute(TransitDto.RouteOptionResponse route) {
        if (!usesSeoulBusSchedules(route) || route.getSegments() == null || route.getSegments().isEmpty()
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
        if (!today().equals(day)) throw unsupported(); // API has no service-date parameter.
        String cacheKey = day + ":" + bus.getTransitName() + ":" + bus.getStartStation() + ":"
                + bus.getEndStation() + ":" + bus.getStartX() + ":" + bus.getStartY() + ":"
                + bus.getEndX() + ":" + bus.getEndY() + ":"
                + bus.getStations().stream().map(TransitDto.RouteStation::getName).toList();
        Cached cached = cache.get(cacheKey);
        if (cached != null && cached.expires().isAfter(clock.instant())) return cached.schedule();
        if (key.isBlank()) {
            log.error("Seoul schedule API key is not configured");
            throw unavailable();
        }
        if (retryAfter.isAfter(clock.instant())) {
            log.warn("Seoul schedule API retry backoff is active");
            throw unavailable();
        }

        List<Element> starts = nearby(bus.getStartStation(), bus.getStartX(), bus.getStartY());
        List<Element> ends = nearby(bus.getEndStation(), bus.getEndX(), bus.getEndY());
        List<RouteSeed> routes = routeCandidates(bus.getTransitName()).stream()
                .filter(candidate -> normalize(candidate.routeName()).equals(normalize(bus.getTransitName())))
                .filter(candidate -> candidate.routeType().isBlank()
                        || Set.of("2", "3", "4", "5", "6").contains(candidate.routeType()))
                .toList();

        List<Binding> matches = new ArrayList<>();
        for (Element start : starts) {
            String ars = text(start, "arsId");
            for (RouteSeed candidate : routes) {
                String routeId = candidate.routeId();
                List<Element> stops = routeStops(routeId, "route-stations");
                for (int i = 0; i < stops.size(); i++) {
                    if (!stationIdOf(stops.get(i)).equals(stationIdOf(start))) continue;
                    for (Element end : ends) {
                        int last = i + bus.getStations().size() - 1;
                        if (last >= stops.size() || !stationIdOf(stops.get(last)).equals(stationIdOf(end))) continue;
                        boolean same = true;
                        for (int j = i; j <= last; j++) {
                            if (!normalize(text(stops.get(j), "stationNm"))
                                    .equals(normalize(bus.getStations().get(j - i).getName()))
                                    || (j > i && sequence(stops.get(j)) != sequence(stops.get(j - 1)) + 1)
                                    || (j > i && j < last && "Y".equals(text(stops.get(j), "transYn")))) same = false;
                        }
                        if (same) matches.add(new Binding(stationIdOf(start), ars, routeId,
                                sequence(stops.get(i)), stationIdOf(end), sequence(stops.get(last))));
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
        if (last.isBefore(first) && last.toLocalDate().equals(first.toLocalDate())) {
            last = last.plusDays(1);
        }
        // The Seoul API can return absolute dates for the previous operating day.
        // At 02:00, an N bus that started at 23:00 yesterday is still today's
        // catchable service. Accept that interval ONLY while it is actually active;
        // never recycle stale yesterday schedules or tomorrow-night departures.
        LocalDateTime now = LocalDateTime.now(clock);
        boolean fromToday = first.toLocalDate().equals(day);
        boolean activePreviousNight = day.equals(today())
                && now.toLocalTime().isBefore(LocalTime.of(6, 0))
                && first.toLocalDate().equals(day.minusDays(1))
                && !now.isBefore(first) && now.isBefore(last);
        if ((!fromToday && !activePreviousNight) || !last.isAfter(first)
                || last.isAfter(first.toLocalDate().plusDays(1).atTime(12, 0))) {
            throw unsupported();
        }
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
        if (!usesSeoulBusSchedules(route) || route.getSegments() == null || route.getSegments().isEmpty()
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

    private TransitDto.RouteOptionResponse toNightRoute(NightRouteCandidate candidate) {
        List<TransitDto.RouteStation> stations = new ArrayList<>();
        for (int i = candidate.startIndex(); i <= candidate.endIndex(); i++) {
            Element stop = candidate.routeStops().get(i);
            stations.add(TransitDto.RouteStation.builder()
                    .name(stationNameOf(stop))
                    .sequence(stations.size() + 1)
                    .stationId(stationIdOf(stop))
                    .x(parseDouble(stationXOf(stop)))
                    .y(parseDouble(stationYOf(stop)))
                    .arsId(text(stop, "arsId"))
                    .build());
        }

        Element startRouteStop = candidate.routeStops().get(candidate.startIndex());
        Element endRouteStop = candidate.routeStops().get(candidate.endIndex());
        int busMinutes = Math.max(5, (candidate.endIndex() - candidate.startIndex()) * 2);

        TransitDto.RouteSegment bus = TransitDto.RouteSegment.builder()
                .transitType("BUS")
                .transitName(candidate.routeName())
                .busType(BusType.TRUNK)
                .nightBus(true)
                .durationMinutes(busMinutes)
                .startStation(stationNameOf(startRouteStop))
                .endStation(stationNameOf(endRouteStop))
                .startX(parseDouble(stationXOf(candidate.originStop())))
                .startY(parseDouble(stationYOf(candidate.originStop())))
                .endX(parseDouble(stationXOf(candidate.destinationStop())))
                .endY(parseDouble(stationYOf(candidate.destinationStop())))
                .stations(stations)
                .odsayStartStationId(stationIdOf(startRouteStop))
                .odsayEndStationId(stationIdOf(endRouteStop))
                .odsayRouteId(candidate.routeId())
                .localCityCode("1000")
                .localStationId(stationIdOf(startRouteStop))
                .localRouteId(candidate.routeId())
                .arsId(text(candidate.originStop(), "arsId"))
                .build();

        return TransitDto.RouteOptionResponse.builder()
                .routeId("SEOUL_NIGHT_" + candidate.routeId() + "_"
                        + stationIdOf(startRouteStop) + "_" + stationIdOf(endRouteStop))
                .provider("SEOUL_NIGHT")
                .totalDurationMinutes(busMinutes + candidate.accessWalkMinutes() + candidate.egressWalkMinutes())
                .realTimeDurationMinutes(busMinutes + candidate.accessWalkMinutes() + candidate.egressWalkMinutes())
                .transferCount(0)
                // Estimated walking at 60 m/min is essential when this discovery
                // route is now used for an actual LAST notification.
                .segments(List.of(
                        TransitDto.RouteSegment.builder().transitType("WALK")
                                .durationMinutes(candidate.accessWalkMinutes())
                                .startStation("출발지").endStation(bus.getStartStation()).build(),
                        bus,
                        TransitDto.RouteSegment.builder().transitType("WALK")
                                .durationMinutes(candidate.egressWalkMinutes())
                                .startStation(bus.getEndStation()).endStation("도착지").build()))
                .build();
    }

    private synchronized List<RouteSeed> routeCandidates(String search) {
        boolean nightList = "N".equalsIgnoreCase(search);
        if (nightList && cachedNightRouteList != null
                && cachedNightRouteList.expires().isAfter(clock.instant())) {
            return cachedNightRouteList.routes();
        }
        List<RouteSeed> fromDatabase = new ArrayList<>();
        if (seoulBusRouteRepository != null) {
            try {
                for (SeoulBusRoute route : seoulBusRouteRepository.findByRouteNmContaining(search)) {
                    if (route.getRouteId() != null && route.getRouteId().matches("[0-9]{9}")
                            && route.getRouteNm() != null && !route.getRouteNm().isBlank()) {
                        fromDatabase.add(new RouteSeed(route.getRouteId(), route.getRouteNm(), ""));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Seoul route DB lookup failed for '{}': {}", search, e.getClass().getSimpleName());
            }
        }
        // A partially synced route table must not hide N-lines that exist in
        // TOPIS. Seoul has 14 N routes; supplement incomplete DB sets with API.
        if (!fromDatabase.isEmpty() && (!"N".equalsIgnoreCase(search)
                || fromDatabase.stream().filter(seed -> TransitRouteClassifier.isNightBusName(seed.routeName())).count() >= 14)) {
            return fromDatabase;
        }

        if (routeListRetryAfter.isAfter(clock.instant())) {
            if (fromDatabase.isEmpty()) throw unavailable();
            return fromDatabase;
        }

        try {
            List<RouteSeed> fromApi = request(
                    "/busRouteInfo/getBusRouteList",
                    Map.of("strSrch", search),
                    "route-list").stream()
                    .map(item -> new RouteSeed(
                            text(item, "busRouteId"),
                            text(item, "busRouteNm"),
                            text(item, "busRouteType")))
                    .filter(route -> route.routeId().matches("[0-9]{9}")
                            && !route.routeName().isBlank())
                    .toList();
            Map<String, RouteSeed> merged = new LinkedHashMap<>();
            for (RouteSeed route : fromDatabase) merged.put(route.routeId(), route);
            for (RouteSeed route : fromApi) merged.put(route.routeId(), route);
            List<RouteSeed> result = List.copyOf(merged.values());
            if (nightList) {
                cachedNightRouteList = new CachedNightRouteList(result, clock.instant().plus(NIGHT_ROUTE_LIST_TTL));
            }
            return result;
        } catch (GlobalException e) {
            if (fromDatabase.isEmpty()) throw e;
            log.info("Seoul night-bus route list API unavailable; using {} cached DB routes", fromDatabase.size());
            return fromDatabase;
        }
    }

    private synchronized List<Element> routeStops(String routeId, String group) {
        CachedRouteStops cached = routeStopsCache.get(routeId);
        if (cached != null && cached.expires().isAfter(clock.instant())) {
            return cached.stops();
        }

        List<Element> stops = request(
                "/busRouteInfo/getStaionByRoute",
                Map.of("busRouteId", routeId),
                group);
        stops.sort(Comparator.comparingInt(this::sequence));

        if (routeStopsCache.size() >= 100) routeStopsCache.clear();
        routeStopsCache.put(routeId,
                new CachedRouteStops(stops, clock.instant().plus(Duration.ofMinutes(30))));
        return stops;
    }

    private List<Element> nearbyByPosition(double x, double y, int radiusMeters) {
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x < 126.7 || x > 127.3 || y < 37.4 || y > 37.75) {
            return List.of();
        }
        return request(
                "/stationinfo/getStationByPos",
                Map.of("tmX", Double.toString(x), "tmY", Double.toString(y),
                        "radius", Integer.toString(radiusMeters)),
                "night-nearby-stations").stream()
                .filter(e -> text(e, "arsId").matches("[0-9]{5}")
                        && !"00000".equals(text(e, "arsId")))
                .filter(e -> stationIdOf(e).matches("[0-9]{9}"))
                .filter(e -> distance(x, y, stationXOf(e), stationYOf(e)) <= radiusMeters)
                .sorted(Comparator.comparingDouble(
                        e -> distance(x, y, stationXOf(e), stationYOf(e))))
                // Dense areas such as Hongdae can have more than 50 stops
                // inside 1 km: truncating here loses the relevant N-bus stop.
                .toList();
    }

    private static String stationIdOf(Element element) {
        return firstNonBlankText(element, "stationId", "stId", "station");
    }

    private static String stationNameOf(Element element) {
        return firstNonBlankText(element, "stationNm", "stNm");
    }

    private static String stationXOf(Element element) {
        return firstNonBlankText(element, "gpsX", "tmX");
    }

    private static String stationYOf(Element element) {
        return firstNonBlankText(element, "gpsY", "tmY");
    }

    private static String firstNonBlankText(Element element, String... names) {
        for (String name : names) {
            String value = text(element, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static Double parseDouble(String value) {
        try {
            return value == null || value.isBlank() ? null : Double.parseDouble(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Element> nearby(String name, Double x, Double y) {
        if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)
                || x < 126.7 || x > 127.3 || y < 37.4 || y > 37.75) throw unsupported();
        var result = request("/stationinfo/getStationByPos",
                Map.of("tmX", x.toString(), "tmY", y.toString(), "radius", "100"), "stations").stream()
                .filter(e -> normalize(stationNameOf(e)).equals(normalize(name)))
                .filter(e -> text(e, "arsId").matches("[0-9]{5}") && !"00000".equals(text(e, "arsId")))
                .filter(e -> stationIdOf(e).matches("[0-9]{9}"))
                .filter(e -> distance(x, y, stationXOf(e), stationYOf(e)) <= 100).toList();
        if (result.isEmpty() || result.size() > 4) throw unsupported();
        return result;
    }

    private List<Element> request(String path, Map<String, String> params, String group) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl + "/api/rest" + path).queryParam("ServiceKey", "{serviceKey}");
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
            Integer status = e instanceof RestClientResponseException response
                    ? response.getStatusCode().value() : null;
            if (status != null && (status == 401 || status == 403)) {
                log.error("Seoul API authentication rejected: endpoint={}, httpStatus={}, keyConfigured={}",
                        path, status, !key.isBlank());
            } else {
                log.warn("Seoul API lookup failed: endpoint={}, failureType={}, httpStatus={}", path,
                        e.getClass().getSimpleName(), status);
            }
            if ("/busRouteInfo/getBusRouteList".equals(path)) {
                routeListRetryAfter = clock.instant().plus(ROUTE_LIST_BACKOFF);
            } else {
                retryAfter = clock.instant().plusSeconds(60);
            }
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
    private record RouteSeed(String routeId, String routeName, String routeType) { }
    private record CachedNightRouteList(List<RouteSeed> routes, Instant expires) { }
    private record CachedRouteStops(List<Element> stops, Instant expires) { }
    private record NightRouteCandidate(
            String routeId,
            String routeName,
            Element originStop,
            Element destinationStop,
            List<Element> routeStops,
            int startIndex,
            int endIndex,
            double score,
            int accessWalkMinutes,
            int egressWalkMinutes) { }
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
