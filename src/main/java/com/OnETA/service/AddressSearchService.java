package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.dto.UserAddressDto.SearchResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Service
public class AddressSearchService {
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;
    private final String restApiKey;


    @Autowired
    public AddressSearchService(ObjectMapper objectMapper,
            @Value("${KAKAO_REST_API_KEY:}") String restApiKey) {
        this(objectMapper, createClient(), restApiKey);
    }

    AddressSearchService(ObjectMapper objectMapper, RestTemplate restTemplate,
                         String restApiKey) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
        this.restApiKey = restApiKey;

    }

    // [1] Search only complete street/lot-number inputs with the address provider.
    // Regular place names keep the existing keyword search path and cost.
    private static final java.util.regex.Pattern ADDRESS_NUMBER =
            java.util.regex.Pattern.compile("[가-힣0-9·]+(?:대로|로|길|동|읍|면|리)\\s*\\d+(?:-\\d+)?(?=\\s|$)");
    private static final java.util.regex.Pattern ATTACHED_NUMBER =
            java.util.regex.Pattern.compile("(?<=[로길동읍면리])(?=\\d+(?:-\\d+)?(?:\\s|$))");

    private static RestTemplate createClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // [2] Each external call is bounded: 1s connect + 2s read.
        // A fallback adds one more bounded request; no automatic retries.
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(2000);
        return new RestTemplate(factory);
    }

    public List<SearchResponse> search(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.strip().length() > 100) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "검색어는 1~100자로 입력해주세요.");
        }
        if (restApiKey.isBlank()) {
            throw new GlobalException(ErrorCode.ADDRESS_SEARCH_UNAVAILABLE);
        }

        String query = keyword.strip();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey.trim());

        if (ADDRESS_NUMBER.matcher(query).find()) {
            // [3] Normalize only a number attached to a street/lot suffix.
            // If address lookup fails, is malformed, or yields no usable entries,
            // retain the old place-keyword lookup as a recovery path.
            String addressQuery = ATTACHED_NUMBER.matcher(query).replaceAll(" ");
            try {
                List<SearchResponse> matches = lookup(addressQuery, headers, true);
                if (!matches.isEmpty()) return matches;
            } catch (Exception ignored) {
                // Never leak provider errors or credentials. Keyword search is next.
            }
        }

        try {
            return lookup(query, headers, false);
        } catch (Exception ignored) {
            throw new GlobalException(ErrorCode.ADDRESS_SEARCH_UNAVAILABLE);
        }
    }

    private List<SearchResponse> lookup(String keyword, HttpHeaders headers, boolean addressSearch)
            throws Exception {
        String endpoint = addressSearch
                ? "https://dapi.kakao.com/v2/local/search/address.json"
                : "https://dapi.kakao.com/v2/local/search/keyword.json";
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("query", "{keyword}").queryParam("size", 5);
        if (!addressSearch) {
            builder.queryParam("sort", "accuracy").queryParam("rect", "124,33,132,39");
        }
        URI uri = builder.encode().buildAndExpand(keyword).toUri();
        com.OnETA.common.ExternalApiCallCounter.record("KAKAO",
                addressSearch ? "address-search" : "keyword-search");
        String body = restTemplate.exchange(uri, HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();
        JsonNode root = objectMapper.readTree(body);
        if (root == null || !root.path("documents").isArray()) {
            throw new IllegalArgumentException("Invalid search response");
        }

        List<SearchResponse> results = new ArrayList<>();
        for (JsonNode item : root.path("documents")) {
            String address;
            String name;
            if (addressSearch) {
                // [4] Address documents have no place_name. Reuse the same
                // {name, address, x, y} contract required by all frontends.
                address = item.path("road_address").path("address_name").asText("").strip();
                if (address.isBlank()) address = item.path("address_name").asText("").strip();
                if (address.isBlank()) address = item.path("address").path("address_name").asText("").strip();
                name = item.path("road_address").path("building_name").asText("").strip();
                if (name.isBlank()) name = address;
            } else {
                name = item.path("place_name").asText("").strip();
                address = item.path("road_address_name").asText("").strip();
                if (address.isBlank()) address = item.path("address_name").asText("").strip();
            }
            if (name.isBlank() || name.length() > 50 || address.isBlank() || address.length() > 255) {
                // Long full addresses can still be returned with a short safe display label.
                if (addressSearch && address.length() <= 255 && !address.isBlank()) {
                    name = address.length() > 50 ? address.substring(0, 50) : address;
                } else continue;
            }
            try {
                double x = Double.parseDouble(item.path("x").asText());
                double y = Double.parseDouble(item.path("y").asText());
                if (!Double.isFinite(x) || !Double.isFinite(y)
                        || x < 124 || x > 132 || y < 33 || y > 39) continue;
                SearchResponse result = new SearchResponse(name, address, x, y);
                if (!results.contains(result)) results.add(result);
                if (results.size() == 5) break;
            } catch (NumberFormatException ignored) {
                // [5] Skip invalid provider coordinates rather than losing valid matches.
            }
        }
        return List.copyOf(results);
    }

    /** Best-effort lookup for push labels; a provider failure must not block delivery. */
    public String reverseGeocode(Double x, Double y) {
        if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y)
                || x < 124 || x > 132 || y < 33 || y > 39 || restApiKey.isBlank()) return null;
        URI uri = UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/local/geo/coord2address.json")
                .queryParam("x", x).queryParam("y", y).queryParam("input_coord", "WGS84")
                .build().toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey.trim());
        try {
            com.OnETA.common.ExternalApiCallCounter.record("KAKAO", "coord2address");
            String body = restTemplate.exchange(uri, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class).getBody();
            JsonNode root = objectMapper.readTree(body);
            if (root == null) return null;
            for (JsonNode item : root.path("documents")) {
                String address = item.path("road_address").path("address_name").asText("").strip();
                if (address.isBlank()) address = item.path("address").path("address_name").asText("").strip();
                if (!address.isBlank()) return address;
            }
        } catch (Exception ignored) {
            // Keep credentials and provider details out of logs and push messages.
        }
        return null;
    }

}

