package com.HomeRun.service;

import com.HomeRun.common.error.ErrorCode;
import com.HomeRun.common.exception.GlobalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class PublicDataTransitService {

    private static final String SEOUL_CITY_CODE = "1000";
    private static final String SEOUL_ARRIVAL_PATH = "/api/rest/arrive/getArrInfoByRouteAll";
    private static final String TAGO_NEARBY_STATION_PATH =
            "/1613000/BusSttnInfoInqireService/getCrdntPrxmtSttnList";
    private static final String TAGO_ARRIVAL_PATH =
            "/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList";
    private static final Duration ARRIVAL_CACHE_TTL = Duration.ofSeconds(20);
    private static final Duration PROVIDER_FAILURE_BACKOFF = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final Map<String, StationMatch> stationCache = new ConcurrentHashMap<>();
    private final Map<String, CachedArrival> arrivalCache = new ConcurrentHashMap<>();
    private volatile Instant seoulUnavailableUntil = Instant.EPOCH;

    @Value("${publicdata.api.key:}")
    private String publicDataApiKey;

    @Value("${publicdata.seoul.url:http://ws.bus.go.kr}")
    private String seoulApiUrl;

    @Value("${publicdata.tago.url:https://apis.data.go.kr}")
    private String tagoApiUrl;

    @Autowired
    public PublicDataTransitService(ObjectMapper objectMapper) {
        this(objectMapper, new RestTemplate());
    }

    PublicDataTransitService(ObjectMapper objectMapper, RestTemplate restTemplate) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    /**
     * Seoul TOPIS is preferred because ODsay exposes the exact local IDs.
     * TAGO is used as a nationwide fallback by resolving the nearest stop.
     */
    public ArrivalEstimate findArrival(
            String localCityCode,
            String localRouteId,
            String localStationId,
            String arsId,
            String routeNumber,
            String stationName,
            Double longitude,
            Double latitude) {
        if (isBlank(routeNumber)) {
            return null;
        }

        String cacheKey = String.join(":",
                nullToEmpty(localCityCode), nullToEmpty(localRouteId),
                nullToEmpty(localStationId), normalizeName(routeNumber),
                coordinateKey(longitude), coordinateKey(latitude));
        ArrivalEstimate cached = getCachedArrival(cacheKey);
        if (cached != null) {
            return cached;
        }

        ArrivalEstimate estimate = null;
        if (SEOUL_CITY_CODE.equals(localCityCode)
                && !isBlank(localRouteId)
                && (!isBlank(localStationId) || !isBlank(arsId))
                && Instant.now().isAfter(seoulUnavailableUntil)) {
            try {
                estimate = findSeoulArrival(localRouteId, localStationId, arsId);
            } catch (GlobalException e) {
                seoulUnavailableUntil = Instant.now().plus(PROVIDER_FAILURE_BACKOFF);
                log.warn("Seoul bus API failed; trying TAGO: {}", e.getMessage());
            }
        }

        if (estimate == null && longitude != null && latitude != null) {
            estimate = findTagoArrival(stationName, longitude, latitude, routeNumber);
        }

        if (estimate != null) {
            arrivalCache.put(cacheKey, new CachedArrival(estimate, Instant.now()));
        }
        return estimate;
    }

    private ArrivalEstimate findSeoulArrival(
            String routeId, String stationId, String arsId) {
        ensureApiKey();
        URI uri = buildUri(normalizeBaseUrl(seoulApiUrl, "http://ws.bus.go.kr") + SEOUL_ARRIVAL_PATH,
                Map.of("busRouteId", routeId));
        try {
            String xml = restTemplate.getForObject(uri, String.class);
            return parseSeoulArrival(xml, routeId, stationId, arsId);
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            throw new GlobalException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "서울시 버스 도착정보 조회에 실패했습니다: " + e.getMessage());
        }
    }

    private ArrivalEstimate findTagoArrival(
            String stationName, double longitude, double latitude, String routeNumber) {
        StationMatch station = resolveTagoStation(stationName, longitude, latitude);
        if (station == null) {
            return null;
        }

        JsonNode items = requestTagoItems(TAGO_ARRIVAL_PATH, Map.of(
                "pageNo", "1",
                "numOfRows", "100",
                "_type", "json",
                "cityCode", station.cityCode(),
                "nodeId", station.stationId()
        ));
        String normalizedRoute = normalizeName(routeNumber);
        return streamItems(items)
                .filter(item -> normalizeName(item.path("routeno").asText()).equals(normalizedRoute))
                .filter(item -> item.path("arrtime").asInt() > 0)
                .min(Comparator.comparingInt(item -> item.path("arrtime").asInt()))
                .map(item -> new ArrivalEstimate(
                        station.cityCode(),
                        station.stationId(),
                        item.path("routeid").asText(null),
                        null,
                        item.path("arrtime").asInt(),
                        "TAGO"))
                .orElse(null);
    }

    private StationMatch resolveTagoStation(
            String stationName, double longitude, double latitude) {
        String cacheKey = String.format(Locale.ROOT, "%.5f:%.5f:%s",
                longitude, latitude, normalizeName(stationName));
        StationMatch cached = stationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        JsonNode items = requestTagoItems(TAGO_NEARBY_STATION_PATH, Map.of(
                "pageNo", "1",
                "numOfRows", "20",
                "_type", "json",
                "gpsLati", Double.toString(latitude),
                "gpsLong", Double.toString(longitude)
        ));
        String normalizedTarget = normalizeName(stationName);
        StationMatch result = streamItems(items)
                .map(item -> new StationCandidate(
                        item.path("citycode").asText(),
                        item.path("nodeid").asText(),
                        item.path("nodenm").asText(),
                        item.path("gpslong").asDouble(longitude),
                        item.path("gpslati").asDouble(latitude)))
                .filter(candidate -> !isBlank(candidate.cityCode()) && !isBlank(candidate.stationId()))
                .min(Comparator.comparingDouble(candidate ->
                        matchScore(normalizedTarget, candidate.name(), longitude, latitude,
                                candidate.longitude(), candidate.latitude())))
                .map(candidate -> new StationMatch(
                        candidate.cityCode(), candidate.stationId(), candidate.name()))
                .orElse(null);
        if (result != null) {
            stationCache.put(cacheKey, result);
        }
        return result;
    }

    ArrivalEstimate parseSeoulArrival(
            String xml, String routeId, String stationId, String arsId) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        Document document = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)));
        String resultCode = firstText(document.getDocumentElement(), "headerCd");
        if (!"0".equals(resultCode)) {
            String message = firstText(document.getDocumentElement(), "headerMsg");
            throw new GlobalException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    isBlank(message) ? "서울시 버스 API 인증 또는 조회에 실패했습니다." : message);
        }

        NodeList items = document.getElementsByTagName("itemList");
        for (int i = 0; i < items.getLength(); i++) {
            Node node = items.item(i);
            if (!(node instanceof Element item)) {
                continue;
            }
            String responseStationId = firstText(item, "stId");
            String responseArsId = firstText(item, "arsId");
            boolean stationMatches = !isBlank(stationId) && stationId.equals(responseStationId);
            boolean arsMatches = !isBlank(arsId)
                    && normalizeArsId(arsId).equals(normalizeArsId(responseArsId));
            if (!stationMatches && !arsMatches) {
                continue;
            }

            int arrivalSeconds = positiveInt(firstText(item, "exps1"));
            if (arrivalSeconds == 0) {
                arrivalSeconds = positiveInt(firstText(item, "traTime1")) * 60;
            }
            if (arrivalSeconds > 0) {
                return new ArrivalEstimate(
                        SEOUL_CITY_CODE, responseStationId, routeId,
                        responseArsId, arrivalSeconds, "SEOUL_TOPIS");
            }
        }
        return null;
    }

    private JsonNode requestTagoItems(String path, Map<String, String> params) {
        ensureApiKey();
        URI uri = buildUri(normalizeBaseUrl(tagoApiUrl, "https://apis.data.go.kr") + path, params);
        try {
            String body = restTemplate.getForObject(uri, String.class);
            JsonNode response = objectMapper.readTree(body).path("response");
            String resultCode = response.path("header").path("resultCode").asText();
            if (!"00".equals(resultCode)) {
                throw new GlobalException(
                        ErrorCode.INTERNAL_SERVER_ERROR,
                        response.path("header").path("resultMsg").asText("TAGO API 오류"));
            }
            return response.path("body").path("items").path("item");
        } catch (GlobalException e) {
            throw e;
        } catch (Exception e) {
            throw new GlobalException(
                    ErrorCode.INTERNAL_SERVER_ERROR, "TAGO 조회에 실패했습니다: " + e.getMessage());
        }
    }

    private URI buildUri(String url, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url)
                .queryParam("serviceKey", decodedApiKey());
        params.forEach(builder::queryParam);
        return builder.encode(StandardCharsets.UTF_8).build().toUri();
    }

    private ArrivalEstimate getCachedArrival(String key) {
        CachedArrival cached = arrivalCache.get(key);
        if (cached == null) {
            return null;
        }
        if (Duration.between(cached.cachedAt(), Instant.now()).compareTo(ARRIVAL_CACHE_TTL) > 0) {
            arrivalCache.remove(key);
            return null;
        }
        return cached.estimate();
    }

    private void ensureApiKey() {
        if (isBlank(publicDataApiKey)) {
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR, "공공데이터 API 키가 설정되지 않았습니다.");
        }
    }

    private String decodedApiKey() {
        String key = publicDataApiKey.trim();
        return key.contains("%") ? URLDecoder.decode(key, StandardCharsets.UTF_8) : key;
    }

    private static Iterable<JsonNode> iterableItems(JsonNode items) {
        if (items.isArray()) {
            return items;
        }
        return items.isObject() ? java.util.List.of(items) : java.util.List.of();
    }

    private static Stream<JsonNode> streamItems(JsonNode items) {
        return StreamSupport.stream(iterableItems(items).spliterator(), false);
    }

    private static double matchScore(
            String targetName, String candidateName,
            double targetX, double targetY, double candidateX, double candidateY) {
        String normalizedCandidate = normalizeName(candidateName);
        double namePenalty = targetName.isBlank() || normalizedCandidate.isBlank()
                ? 100
                : targetName.equals(normalizedCandidate) ? 0
                : targetName.contains(normalizedCandidate) || normalizedCandidate.contains(targetName)
                ? 20 : 200;
        return namePenalty + haversineMeters(targetY, targetX, candidateY, candidateX);
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    static String normalizeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", "")
                .replaceAll("[^0-9a-z가-힣]", "");
    }

    private static String normalizeBaseUrl(String url, String defaultUrl) {
        String value = isBlank(url) ? defaultUrl : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String firstText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static int positiveInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String normalizeArsId(String value) {
        return value == null ? "" : value.replace("-", "").trim();
    }

    private static String coordinateKey(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.5f", value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record ArrivalEstimate(
            String cityCode,
            String stationId,
            String routeId,
            String arsId,
            int arrivalSeconds,
            String source) {
    }

    private record StationMatch(String cityCode, String stationId, String name) {
    }

    private record StationCandidate(
            String cityCode, String stationId, String name, double longitude, double latitude) {
    }

    private record CachedArrival(ArrivalEstimate estimate, Instant cachedAt) {
    }
}
