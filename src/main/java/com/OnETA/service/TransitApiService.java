package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.TransitDto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
@Slf4j
public class TransitApiService {

    private final PublicDataTransitService publicDataTransitService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final UserAddressService userAddressService;

    @Value("${odsay.api.key:}")
    private String odsayApiKey;

    @Value("${odsay.api.url:https://api.odsay.com/v1/api/searchPubTransPathR}")
    private String odsayApiUrl;

    @Value("${odsay.api.referer:http://localhost:8080/}")
    private String odsayReferer;

    @Autowired
    public TransitApiService(PublicDataTransitService publicDataTransitService, ObjectMapper objectMapper,
                             UserAddressService userAddressService) {
        this(publicDataTransitService, objectMapper, new RestTemplate(), userAddressService);
    }

    TransitApiService(PublicDataTransitService publicDataTransitService,
                      ObjectMapper objectMapper,
                      RestTemplate restTemplate) {
        this(publicDataTransitService, objectMapper, restTemplate, null);
    }

    private TransitApiService(PublicDataTransitService publicDataTransitService,
                              ObjectMapper objectMapper,
                              RestTemplate restTemplate,
                              UserAddressService userAddressService) {
        this.publicDataTransitService = publicDataTransitService;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.userAddressService = userAddressService;
    }

    /**
     * Recalculates a saved route using current bus arrival predictions.
     * Non-bus section times remain based on ODsay's estimate.
     */
    public int getRealTimeDuration(String routeDetails) {
        if (routeDetails == null || routeDetails.isBlank()) {
            throw new GlobalException(
                    ErrorCode.INVALID_INPUT_VALUE, "저장된 경로 정보가 없습니다.");
        }

        try {
            TransitDto.RouteOptionResponse route = readSavedRoute(routeDetails);
            return enrichWithRealTimeArrivals(route).getRealTimeDurationMinutes();
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            throw new GlobalException(
                    ErrorCode.INVALID_INPUT_VALUE, "저장된 경로 정보를 읽을 수 없습니다.");
        }
    }

    public TransitDto.RouteOptionResponse readSavedRoute(String routeDetails) {
        if (routeDetails == null || routeDetails.isBlank()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "저장된 경로 정보가 없습니다.");
        }
        try {
            TransitDto.RouteOptionResponse route =
                    objectMapper.readValue(routeDetails, TransitDto.RouteOptionResponse.class);
            List<TransitDto.RouteSegment> segments = route.getSegments() == null
                    ? List.of()
                    : route.getSegments().stream().map(this::withFallbackStations).toList();
            return route.toBuilder().segments(segments).build();
        } catch (Exception e) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "저장된 경로 정보를 읽을 수 없습니다.");
        }
    }

    private TransitDto.RouteSegment withFallbackStations(TransitDto.RouteSegment segment) {
        if (segment.getStations() != null && !segment.getStations().isEmpty()) return segment;
        List<TransitDto.RouteStation> stations = new ArrayList<>();
        if (segment.getStartStation() != null && !segment.getStartStation().isBlank()) {
            stations.add(TransitDto.RouteStation.builder().name(segment.getStartStation()).sequence(1)
                    .stationId(segment.getOdsayStartStationId()).x(segment.getStartX()).y(segment.getStartY())
                    .arsId(segment.getArsId()).build());
        }
        if (segment.getEndStation() != null && !segment.getEndStation().isBlank()
                && !segment.getEndStation().equals(segment.getStartStation())) {
            stations.add(TransitDto.RouteStation.builder().name(segment.getEndStation())
                    .sequence(stations.size() + 1).build());
        }
        return segment.toBuilder().stations(stations).build();
    }

    public List<TransitDto.RouteOptionResponse> searchRoutes(
            Double originX, Double originY, Double destX, Double destY) {

        validateCoordinates(originX, originY, destX, destY);
        if (odsayApiKey == null || odsayApiKey.isBlank()) {
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR, "ODsay API 키가 설정되지 않았습니다.");
        }

        URI uri = UriComponentsBuilder.fromUriString(odsayApiUrl)
                .queryParam("SX", originX)
                .queryParam("SY", originY)
                .queryParam("EX", destX)
                .queryParam("EY", destY)
                .queryParam("apiKey", odsayApiKey.trim())
                .encode(StandardCharsets.UTF_8)
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.REFERER, normalizeReferer(odsayReferer));
        headers.set(HttpHeaders.ORIGIN, originFromReferer(odsayReferer));

        try {
            log.debug("ODsay search request: host={}, endpoint={}, odsayApiKeyConfigured={}",
                    uri.getHost(), uri.getPath(), odsayApiKey != null && !odsayApiKey.isBlank());
            ResponseEntity<String> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return parseOdsayResponse(response.getBody()).stream()
                    .map(this::enrichWithRealTimeArrivals)
                    .toList();
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            throw new GlobalException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "경로 검색에 실패했습니다: " + e.getMessage());
        }
    }

    public List<TransitDto.RouteOptionResponse> searchRoutes(
            String email, Double originX, Double originY, String originAddress,
            Double destX, Double destY, String destAddress) {
        if ((destX == null) != (destY == null)) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "목적지 x, y 좌표는 함께 입력해주세요.");
        }
        if (destX == null) {
            // 직접 입력한 목적지가 없을 때만 사용자의 현재 주소를 기본 목적지로 사용한다.
            com.OnETA.entity.UserAddress currentAddress = userAddressService.getCurrentEntity(email);
            destX = currentAddress.getX();
            destY = currentAddress.getY();
            destAddress = currentAddress.getAddress();
        }
        final String resolvedOriginAddress = normalizeAddressText(originAddress);
        final String resolvedDestinationAddress = normalizeAddressText(destAddress);
        return searchRoutes(originX, originY, destX, destY).stream()
                .map(route -> route.toBuilder()
                        .originAddress(resolvedOriginAddress)
                        .destinationAddress(resolvedDestinationAddress)
                        .build())
                .toList();
    }

    private static String normalizeAddressText(String address) {
        if (address == null || address.isBlank()) return null;
        String normalized = address.trim();
        if (normalized.length() > 255) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "주소는 255자 이하여야 합니다.");
        }
        return normalized;
    }

    TransitDto.RouteOptionResponse enrichWithRealTimeArrivals(TransitDto.RouteOptionResponse route) {
        int totalMinutes = route.getTotalDurationMinutes() == null ? 0 : route.getTotalDurationMinutes();
        boolean hasRealTimeArrival = false;
        List<TransitDto.RouteSegment> enrichedSegments = new ArrayList<>();

        for (TransitDto.RouteSegment segment : route.getSegments()) {
            TransitDto.RouteSegment enriched = segment;
            int sectionMinutes = segment.getDurationMinutes() == null ? 0 : segment.getDurationMinutes();

            if ("BUS".equals(segment.getTransitType())) {
                try {
                    PublicDataTransitService.ArrivalEstimate estimate =
                            publicDataTransitService.findArrival(
                                    segment.getLocalCityCode(),
                                    segment.getLocalRouteId(),
                                    segment.getLocalStationId(),
                                    segment.getArsId(),
                                    segment.getTransitName(),
                                    segment.getStartStation(),
                                    segment.getStartX(),
                                    segment.getStartY());

                    if (estimate != null) {
                        int waitMinutes = Math.max(1, (int) Math.ceil(estimate.arrivalSeconds() / 60.0));
                        int scheduledWaitMinutes = segment.getScheduledWaitMinutes() == null
                                ? 0
                                : segment.getScheduledWaitMinutes();
                        totalMinutes += waitMinutes - scheduledWaitMinutes;
                        hasRealTimeArrival = true;
                        enriched = segment.toBuilder()
                                .localCityCode(estimate.cityCode())
                                .localStationId(estimate.stationId())
                                .localRouteId(estimate.routeId())
                                .arsId(estimate.arsId())
                                .realTimeArrivalSeconds(estimate.arrivalSeconds())
                                .realTimeSource(estimate.source())
                                .build();
                    }
                } catch (GlobalException e) {
                    log.warn("Real-time bus lookup failed; using ODsay duration: {}", e.getMessage());
                }
            }

            enrichedSegments.add(enriched);
        }

        return route.toBuilder()
                .realTimeDurationMinutes(Math.max(0, totalMinutes))
                .segments(enrichedSegments)
                .build();
    }

    boolean hasSameOdsayApiKey(String candidate) {
        String normalizedCandidate = candidate == null ? "" : candidate.trim();
        String normalizedKey = odsayApiKey == null ? "" : odsayApiKey.trim();
        return normalizedKey.equals(normalizedCandidate);
    }

    private List<TransitDto.RouteOptionResponse> parseOdsayResponse(String jsonString) {
        try {
            JsonNode root = objectMapper.readTree(jsonString);
            if (root.has("error")) {
                String message = root.path("error").path("message").asText("ODsay API 오류");
                throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR, message);
            }

            JsonNode paths = root.path("result").path("path");
            if (!paths.isArray()) {
                throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR, "ODsay 경로 응답 형식이 올바르지 않습니다.");
            }

            List<TransitDto.RouteOptionResponse> results = new ArrayList<>();
            for (JsonNode path : paths) {
                JsonNode info = path.path("info");
                List<TransitDto.RouteSegment> segments = new ArrayList<>();

                for (JsonNode subPath : path.path("subPath")) {
                    int trafficType = subPath.path("trafficType").asInt();
                    JsonNode lane = subPath.path("lane").isArray() && !subPath.path("lane").isEmpty()
                            ? subPath.path("lane").get(0)
                            : objectMapper.createObjectNode();

                    String transitType = switch (trafficType) {
                        case 1 -> "SUBWAY";
                        case 2 -> "BUS";
                        default -> "WALK";
                    };
                    String transitName = trafficType == 2
                            ? lane.path("busNo").asText("")
                            : lane.path("name").asText("");

                    segments.add(TransitDto.RouteSegment.builder()
                            .transitType(transitType)
                            .startStation(subPath.path("startName").asText(""))
                            .endStation(subPath.path("endName").asText(""))
                            .durationMinutes(subPath.path("sectionTime").asInt())
                            .transitName(transitName)
                            .odsayStartStationId(textOrNull(subPath, "startID"))
                            .odsayEndStationId(textOrNull(subPath, "endID"))
                            .odsayRouteId(textOrNull(lane, "busID"))
                            .busProviderCode(textOrNull(lane, "busProviderCode"))
                            .subwayCode(textOrNull(lane, "subwayCode"))
                            .subwayCityCode(textOrNull(lane, "subwayCityCode"))
                            .way(textOrNull(subPath, "way"))
                            .wayCode(textOrNull(subPath, "wayCode"))
                            .localCityCode(textOrNull(lane, "busCityCode"))
                            .localRouteId(textOrNull(lane, "busLocalBlID"))
                            .localStationId(textOrNull(subPath, "startLocalStationID"))
                            .arsId(textOrNull(subPath, "startArsID"))
                            .scheduledWaitMinutes(trafficType == 2
                                    ? expectedWaitMinutes(subPath)
                                    : null)
                            .startX(doubleOrNull(subPath, "startX"))
                            .startY(doubleOrNull(subPath, "startY"))
                            .endX(doubleOrNull(subPath, "endX"))
                            .endY(doubleOrNull(subPath, "endY"))
                            .stations(parseStations(subPath))
                            .build());
                }

                results.add(TransitDto.RouteOptionResponse.builder()
                        .routeId(stableRouteId(path))
                        .totalDurationMinutes(info.path("totalTime").asInt())
                        .totalCost(info.path("payment").asInt())
                        .transferCount(info.path("transitCount").asInt())
                        .segments(segments)
                        .build());

                if (results.size() >= 3) {
                    break;
                }
            }
            return results;
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR, "ODsay 응답 파싱에 실패했습니다.");
        }
    }

    private List<TransitDto.RouteStation> parseStations(JsonNode subPath) {
        JsonNode stationNodes = subPath.path("passStopList").path("stations");
        if (!stationNodes.isArray()) return List.of();
        List<TransitDto.RouteStation> stations = new ArrayList<>();
        int sequence = 1;
        for (JsonNode station : stationNodes) {
            stations.add(TransitDto.RouteStation.builder()
                    .name(station.path("stationName").asText(""))
                    .sequence(sequence++)
                    .stationId(textOrNull(station, "stationID"))
                    .x(doubleOrNull(station, "x"))
                    .y(doubleOrNull(station, "y"))
                    .arsId(textOrNull(station, "stationArsID"))
                    .build());
        }
        return stations;
    }

    private String stableRouteId(JsonNode path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(path.toString().getBytes(StandardCharsets.UTF_8));
        return "ROUTE_" + HexFormat.of().formatHex(hash, 0, 8);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.asDouble();
    }

    private static int expectedWaitMinutes(JsonNode subPath) {
        int intervalMinutes = subPath.path("intervalTime").asInt(0);
        return intervalMinutes <= 0 ? 0 : Math.max(1, (int) Math.ceil(intervalMinutes / 2.0));
    }

    private static void validateCoordinates(Double originX, Double originY, Double destX, Double destY) {
        if (originX == null || originY == null || destX == null || destY == null
                || originX < 124 || originX > 132 || destX < 124 || destX > 132
                || originY < 33 || originY > 39 || destY < 33 || destY > 39) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "대한민국 범위의 출발지와 목적지 좌표를 입력해주세요.");
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
}
