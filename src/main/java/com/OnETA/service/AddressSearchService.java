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

    private static RestTemplate createClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return new RestTemplate(factory);
    }

    public List<SearchResponse> search(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.strip().length() > 100) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "검색어는 1~100자로 입력해주세요.");
        }
        if (restApiKey.isBlank()) {
            throw new GlobalException(ErrorCode.ADDRESS_SEARCH_UNAVAILABLE);
        }
        URI uri = UriComponentsBuilder.fromUriString("https://dapi.kakao.com/v2/local/search/keyword.json")
                .queryParam("query", "{keyword}").queryParam("size", 5).queryParam("sort", "accuracy")
                .queryParam("rect", "124,33,132,39")
                .encode().buildAndExpand(keyword.strip()).toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey.trim());

        try {
            com.OnETA.common.ExternalApiCallCounter.record("KAKAO", "keyword-search");
            String body = restTemplate.exchange(uri, HttpMethod.GET,
                    new HttpEntity<>(headers), String.class).getBody();
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.path("documents").isArray()) {
                throw new IllegalArgumentException("Invalid search response");
            }
            List<SearchResponse> results = new ArrayList<>();
            for (JsonNode item : root.path("documents")) {
                String name = item.path("place_name").asText("").strip();
                String address = item.path("road_address_name").asText("").strip();
                if (address.isBlank()) address = item.path("address_name").asText("").strip();
                double x = Double.parseDouble(item.path("x").asText());
                double y = Double.parseDouble(item.path("y").asText());
                if (name.isBlank() || name.length() > 50 || address.isBlank() || address.length() > 255
                        || !Double.isFinite(x) || !Double.isFinite(y)
                        || x < 124 || x > 132 || y < 33 || y > 39) continue;
                SearchResponse result = new SearchResponse(name, address, x, y);
                if (!results.contains(result)) results.add(result);
                if (results.size() == 5) break;
            }
            return List.copyOf(results);
        } catch (Exception e) {
            // Do not expose provider errors, request headers or credentials to clients.
            throw new GlobalException(ErrorCode.ADDRESS_SEARCH_UNAVAILABLE);
        }
    }

}

