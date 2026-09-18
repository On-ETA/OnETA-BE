package com.OnETA.service;

import com.OnETA.common.ExternalApiCallCounter;
import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
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

@Service
@Slf4j
public class KakaoTransitClient {
    private final ObjectMapper mapper;
    private final RestTemplate restTemplate;
    private final String key;
    private final boolean enabled;
    private static final String URL = "https://dapi.kakao.com/v2/routing/publictraffic";

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
        if (!isConfigured()) throw new GlobalException(ErrorCode.TRANSIT_API_UNAVAILABLE);
        var uri = UriComponentsBuilder.fromUriString(URL)
                .queryParam("start_x", sx).queryParam("start_y", sy)
                .queryParam("end_x", ex).queryParam("end_y", ey).build().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "KakaoAK " + key);
        try {
            ExternalApiCallCounter.record("KAKAO", "publictraffic");
            var response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return parse(response.getBody());
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Kakao transit request failed: {}", e.getClass().getSimpleName());
            throw new GlobalException(ErrorCode.TRANSIT_API_UNAVAILABLE);
        }
    }

    List<TransitDto.RouteOptionResponse> parse(String body) {
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
                if (result.size() == 3) break;
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
        if (!"WALK".equals(type) && (stations.size() < 2 || name.isBlank())) throw invalid();
        JsonNode points = step.path("path").path("points");
        JsonNode first = points.isArray() && !points.isEmpty() ? points.get(0) : null;
        JsonNode last = points.isArray() && !points.isEmpty() ? points.get(points.size() - 1) : null;
        return TransitDto.RouteSegment.builder().transitType(type).transitName(name)
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
