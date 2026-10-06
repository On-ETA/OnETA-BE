package com.OnETA.service;

import com.OnETA.common.ExternalApiCallCounter;
import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.BusType;
import com.OnETA.dto.TransitDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class KakaoTransitClient {
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;
    private final String key;
    private final boolean enabled;
    private static final String URL = "https://dapi.kakao.com/v2/routing/publictraffic";
    private static final String WALK_URL = "https://dapi.kakao.com/v2/routing/walk";

    @Autowired
    public KakaoTransitClient(ObjectMapper mapper,
                              @Value("${KAKAO_REST_API_KEY:}") String key,
                              @Value("${kakao.transit.fallback-enabled:true}") boolean enabled) {
        this(mapper, createRestTemplate(), key, enabled);
    }

    KakaoTransitClient(ObjectMapper mapper, RestTemplate restTemplate, String key, boolean enabled) {
        this.mapper = mapper;
        this.restTemplate = restTemplate;
        this.key = key == null ? "" : key.trim();
        this.enabled = enabled;
    }

    private static RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        return new RestTemplate(factory);
    }

    boolean isConfigured() { return enabled && !key.isBlank(); }

    public List<TransitDto.RouteOptionResponse> search(double sx, double sy, double ex, double ey) {
        return search(sx, sy, ex, ey, 3);
    }

    public List<TransitDto.RouteOptionResponse> search(
            double sx, double sy, double ex, double ey, int maxCandidates) {
        HttpHeaders headers = authorizationHeaders();
        try {
            List<TransitDto.RouteOptionResponse> routes =
                    requestTransitRoutes(sx, sy, ex, ey, maxCandidates, headers);
            // Different alternatives often share the same access or egress walk.
            Map<WalkLeg, Integer> walkingTimes = new HashMap<>();
            return routes.stream()
                    .map(route -> completeEndpointWalks(route, sx, sy, ex, ey, headers, walkingTimes))
                    .toList();
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Kakao transit request failed: {}", e.getClass().getSimpleName());
            throw new GlobalException(ErrorCode.TRANSIT_API_UNAVAILABLE);
        }
    }

    public List<TransitDto.RouteOptionResponse> searchScheduleCandidates(
            double sx, double sy, double ex, double ey, int maxCandidates) {
        // FIRST/LAST 결과도 일반 경로 검색과 동일하게 출발/도착 도보 구간을 완성한다.
        return search(sx, sy, ex, ey, maxCandidates);
    }

    TransitDto.RouteOptionResponse completeEndpointWalks(
            TransitDto.RouteOptionResponse route,
            double sx, double sy, double ex, double ey) {
        HttpHeaders headers = authorizationHeaders();
        Map<WalkLeg, Integer> walkingTimes = new HashMap<>();
        return completeEndpointWalks(route, sx, sy, ex, ey, headers, walkingTimes);
    }

    private HttpHeaders authorizationHeaders() {
        if (!isConfigured()) throw new GlobalException(ErrorCode.TRANSIT_API_UNAVAILABLE);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "KakaoAK " + key);
        return headers;
    }

    private List<TransitDto.RouteOptionResponse> requestTransitRoutes(
            double sx, double sy, double ex, double ey, int maxCandidates, HttpHeaders headers) {
        var uri = UriComponentsBuilder.fromUriString(URL)
                .queryParam("start_x", sx).queryParam("start_y", sy)
                .queryParam("end_x", ex).queryParam("end_y", ey).build().toUri();
        ExternalApiCallCounter.record("KAKAO", "publictraffic");
        var response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return parse(response.getBody(), maxCandidates);
    }

    private TransitDto.RouteOptionResponse completeEndpointWalks(TransitDto.RouteOptionResponse route,
            double sx, double sy, double ex, double ey, HttpHeaders headers, Map<WalkLeg, Integer> walkingTimes) {
        var original = route.getSegments();
        var first = original.get(0);
        var last = original.get(original.size() - 1);
        List<TransitDto.RouteSegment> segments = new ArrayList<>();
        if (!"WALK".equals(first.getTransitType())) {
            addEndpointWalk(segments, new WalkLeg(sx, sy, first.getStartX(), first.getStartY()),
                    "", first.getStartStation(), headers, walkingTimes);
        }
        segments.addAll(original);
        if (!"WALK".equals(last.getTransitType())) {
            addEndpointWalk(segments, new WalkLeg(last.getEndX(), last.getEndY(), ex, ey),
                    last.getEndStation(), "", headers, walkingTimes);
        }
        // Provider totalTime already accounts for more than the vehicle steps.
        // Preserve it; adding the new walk times again would double-count them.
        return route.toBuilder().segments(segments).build();
    }

    private void addEndpointWalk(List<TransitDto.RouteSegment> segments, WalkLeg leg,
            String startName, String endName, HttpHeaders headers, Map<WalkLeg, Integer> walkingTimes) {
        if (!leg.hasValidCoordinates()) throw invalid();
        // Allow a small coordinate precision/snap difference at the same stop.
        if (leg.distanceMeters() <= 5) return;
        int duration = walkingTimes.computeIfAbsent(leg, key -> fetchWalkingMinutes(key, headers));
        segments.add(TransitDto.RouteSegment.builder().transitType("WALK").transitName("")
                .startStation(startName).endStation(endName).durationMinutes(duration)
                .startX(leg.sx()).startY(leg.sy()).endX(leg.ex()).endY(leg.ey())
                .stations(List.of()).build());
    }

    private int fetchWalkingMinutes(WalkLeg leg, HttpHeaders headers) {
        var uri = UriComponentsBuilder.fromUriString(WALK_URL)
                .queryParam("start_x", leg.sx()).queryParam("start_y", leg.sy())
                .queryParam("end_x", leg.ex()).queryParam("end_y", leg.ey()).build().toUri();
        ExternalApiCallCounter.record("KAKAO", "walk");
        var response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        try {
            var root = mapper.readTree(response.getBody());
            if (root == null || !root.isObject()) throw invalid();
            if ("SAME_POINT".equals(root.path("status").asText())) return 0;
            if (!"OK".equals(root.path("status").asText())) throw invalid();
            return minutes(root.path("route").path("properties"), "totalTime");
        } catch (Exception e) {
            throw invalid();
        }
    }

    private record WalkLeg(Double sx, Double sy, Double ex, Double ey) {
        boolean hasValidCoordinates() {
            return valid(sx, 180) && valid(ex, 180) && valid(sy, 90) && valid(ey, 90);
        }
        private static boolean valid(Double value, int bound) {
            return value != null && Double.isFinite(value) && Math.abs(value) <= bound;
        }
        double distanceMeters() {
            double dLat = Math.toRadians(ey - sy), dLon = Math.toRadians(ex - sx);
            double a = Math.pow(Math.sin(dLat / 2), 2)
                    + Math.cos(Math.toRadians(sy)) * Math.cos(Math.toRadians(ey))
                    * Math.pow(Math.sin(dLon / 2), 2);
            return 6371000 * 2 * Math.asin(Math.sqrt(Math.min(1, a)));
        }
    }

    List<TransitDto.RouteOptionResponse> parse(String body) {
        return parse(body, 3);
    }

    List<TransitDto.RouteOptionResponse> parse(String body, int maxCandidates) {
        try {
            JsonNode root = mapper.readTree(body);
            if (root == null || !root.isObject()) throw invalid();
            switch (root.path("status").asText("")) {
                case "OK" -> { }
                case "NO_RESULTS", "STARTNODES_NULL", "ENDNODES_NULL" ->
                        throw new GlobalException(ErrorCode.TRANSIT_ROUTE_NOT_FOUND);
                case "EQUAL_POINTS", "INVALID_REQUEST" ->
                        throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE);
                default -> throw invalid();
            }
            JsonNode routes = root.path("routes");
            if (!routes.isArray()) throw invalid();
            if (routes.isEmpty()) throw new GlobalException(ErrorCode.TRANSIT_ROUTE_NOT_FOUND);
            List<TransitDto.RouteOptionResponse> result = new ArrayList<>();
            for (JsonNode route : routes) {
                JsonNode props = route.path("properties");
                int minutes = minutes(props, "totalTime");
                int transfers = nonNegativeInt(props, "transfers");
                JsonNode steps = route.path("steps");
                if (!steps.isArray() || steps.isEmpty()) throw invalid();
                List<TransitDto.RouteSegment> segments = new ArrayList<>();
                for (JsonNode step : steps) segments.add(segment(step));
                JsonNode fare = props.path("fare");
                Integer cost = null;
                if (fare.hasNonNull("value")) cost = nonNegativeInt(fare, "value");
                else if (fare.hasNonNull("min")) cost = nonNegativeInt(fare, "min");
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest(route.toString().getBytes(StandardCharsets.UTF_8));
                result.add(TransitDto.RouteOptionResponse.builder()
                        .routeId("KAKAO_" + HexFormat.of().formatHex(hash, 0, 8)).provider("KAKAO")
                        .totalDurationMinutes(minutes).realTimeDurationMinutes(minutes)
                        .totalCost(cost).transferCount(transfers).segments(segments).build());
                if (result.size() >= Math.max(1, Math.min(maxCandidates, 10))) break;
            }
            return result;
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            throw invalid();
        }
    }

    private TransitDto.RouteSegment segment(JsonNode step) {
        JsonNode props = step.path("properties");
        String type = switch (props.path("type").asText("")) {
            case "WALKING" -> "WALK";
            case "BUS" -> "BUS";
            case "SUBWAY" -> "SUBWAY";
            default -> throw new GlobalException(ErrorCode.TRANSIT_ROUTE_UNSUPPORTED);
        };
        List<TransitDto.RouteStation> stations = new ArrayList<>();
        JsonNode stops = props.path("stops");
        if (stops.isArray()) {
            for (JsonNode stop : stops) {
                String name = stop.path("name").asText("");
                if (name.isBlank()) throw invalid();
                stations.add(TransitDto.RouteStation.builder().name(name).sequence(stations.size() + 1).build());
            }
        }
        JsonNode vehicles = props.path("vehicles");
        String name = vehicles.isArray() && !vehicles.isEmpty() ? vehicles.get(0).path("name").asText("") : "";
        String vehicleType = vehicles.isArray() && !vehicles.isEmpty()
                ? vehicles.get(0).path("type").asText("")
                : "";
        if (!"WALK".equals(type) && (stations.size() < 2 || name.isBlank())) throw invalid();
        JsonNode points = step.path("path").path("points");
        JsonNode first = points.isArray() && !points.isEmpty() ? points.get(0) : null;
        JsonNode last = points.isArray() && !points.isEmpty() ? points.get(points.size() - 1) : null;
        return TransitDto.RouteSegment.builder().transitType(type).transitName(name)
                .busType("BUS".equals(type) ? BusType.fromKakao(vehicleType) : null)
                .nightBus("BUS".equals(type) && TransitRouteClassifier.isNightBusName(name))
                .durationMinutes(minutes(props, "time"))
                .startStation(stations.isEmpty() ? "" : stations.get(0).getName())
                .endStation(stations.isEmpty() ? "" : stations.get(stations.size() - 1).getName())
                .startX(coordinate(first, 0)).startY(coordinate(first, 1))
                .endX(coordinate(last, 0)).endY(coordinate(last, 1)).stations(stations).build();
    }

    private static Double coordinate(JsonNode point, int index) {
        return point != null && point.isArray() && point.size() >= 2 && point.get(index).isNumber()
                ? point.get(index).asDouble() : null;
    }

    private static int minutes(JsonNode node, String field) {
        return (int) Math.ceil(nonNegativeInt(node, field) / 60.0);
    }

    private static int nonNegativeInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 0) throw invalid();
        return value.asInt();
    }

    private static GlobalException invalid() { return new GlobalException(ErrorCode.TRANSIT_INVALID_RESPONSE); }
}
