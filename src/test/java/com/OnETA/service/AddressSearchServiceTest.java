package com.OnETA.service;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AddressSearchServiceTest {
    private final RestTemplate client = mock(RestTemplate.class);
    private final AddressSearchService service = new AddressSearchService(new ObjectMapper(), client, "secret");

    @Test
    void controllerRequiresLoginAndReturnsEnvelopeWithoutSaving() {
        var search = mock(AddressSearchService.class);
        var addresses = mock(UserAddressService.class);
        var controller = new com.OnETA.controller.UserAddressController(addresses, search);
        when(search.search("학교")).thenReturn(java.util.List.of(
                new com.OnETA.dto.UserAddressDto.SearchResponse("학교", "주소", 127d, 37d)));
        assertThatThrownBy(() -> controller.search(null, "학교"))
                .isInstanceOfSatisfying(GlobalException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        verifyNoInteractions(search);
        var response = controller.search(() -> "user@example.com", "학교");
        assertThat(response.getCode()).isEqualTo("SUCCESS");
        assertThat(response.getData()).hasSize(1);
        verifyNoInteractions(addresses);
    }

    @Test
    void convertsCoordinatesAndNamesAndFallsBackToJibun() {
        when(client.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(invocation -> {
                    URI uri = invocation.getArgument(0);
                    assertThat(uri.getHost()).isEqualTo("dapi.kakao.com");
                    assertThat(uri.getPath()).isEqualTo("/v2/local/search/keyword.json");
                    assertThat(uri.getQuery()).contains("rect=124,33,132,39", "sort=accuracy");
                    assertThat(uri.getRawQuery()).contains("%26", "%2B", "size=5");
                    HttpEntity<?> request = invocation.getArgument(2);
                    assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("KakaoAK secret");
                    return ResponseEntity.ok("""
                        {"documents":[{"place_name":"학교 & 기숙사","road_address_name":"도로명 주소","address_name":"지번 주소","x":"126.9256","y":"37.5515"},
                        {"place_name":"다른 장소","road_address_name":"","address_name":"지번 주소","x":"127.0","y":"37.0"}]}
                        """);
                });
        var results = service.search(" 학교 & 기숙사+ ");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).name()).isEqualTo("학교 & 기숙사");
        assertThat(results.get(0).address()).isEqualTo("도로명 주소");
        assertThat(results.get(0).x()).isEqualTo(126.9256);
        assertThat(results.get(0).y()).isEqualTo(37.5515);
        assertThat(results.get(1).address()).isEqualTo("지번 주소");
    }

    @Test
    void emptyResultsAreSuccessful() {
        when(client.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"documents\":[]}"));
        assertThat(service.search("없는 장소")).isEmpty();
    }

    @Test
    void rejectsInvalidKeywordsBeforeCallingProvider() {
        for (String keyword : new String[]{null, "   ", "a".repeat(101)}) {
            assertThatThrownBy(() -> service.search(keyword)).isInstanceOfSatisfying(GlobalException.class,
                    e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
        }
        verifyNoInteractions(client);
    }

    @Test
    void providerErrorsDoNotLeakDetails() {
        when(client.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("secret provider detail"));
        assertThatThrownBy(() -> service.search("학교")).isInstanceOfSatisfying(GlobalException.class,
                e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ADDRESS_SEARCH_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("secret");
                });
    }

    @Test
    void malformedResponseIsNotReportedAsNoResults() {
        when(client.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{}"));
        assertThatThrownBy(() -> service.search("학교")).isInstanceOf(GlobalException.class);
    }

    @Test
    void missingCredentialsPreventExternalCall() {
        var unconfigured = new AddressSearchService(new ObjectMapper(), client, "");
        assertThatThrownBy(() -> unconfigured.search("학교")).isInstanceOf(GlobalException.class);
        verifyNoInteractions(client);
    }
}

