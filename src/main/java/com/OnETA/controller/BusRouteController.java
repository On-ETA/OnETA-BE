package com.OnETA.controller;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.common.response.ApiResponse;
import com.OnETA.dto.bus.BusDirectionResponseDto;
import com.OnETA.dto.bus.BusLocationResponseDto;
import com.OnETA.dto.bus.BusRouteSearchResponseDto;
import com.OnETA.service.SeoulBusLocationService;
import com.OnETA.service.SeoulBusRouteSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// 서울 버스 검색, 방면 정보 조회, 실시간 위치 조회 컨트롤러
@RestController
@RequestMapping("/api/bus-routes")
@RequiredArgsConstructor
public class BusRouteController {

    private final SeoulBusRouteSearchService seoulBusRouteSearchService;
    private final SeoulBusLocationService seoulBusLocationService;

    // routeNm 검색 API
    // GET /api/bus-routes/search
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<BusRouteSearchResponseDto>>> searchBusRoutes(
            @RequestParam(name = "query") String keyword) {

        if (keyword == null || keyword.trim().isEmpty()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "검색어를 입력해주세요.");
        }

        List<BusRouteSearchResponseDto> results = seoulBusRouteSearchService.searchBusRoutes(keyword.trim());

        return ResponseEntity.ok(ApiResponse.success(results));
    }

    // 특정 노선의 운행 방면 정보 조회 API
    // GET /api/bus-routes/{routeId}/directions
    @GetMapping("/{routeId}/directions")
    public ResponseEntity<ApiResponse<BusDirectionResponseDto>> getBusDirections(
            @PathVariable("routeId") String routeId) {

        BusDirectionResponseDto response = seoulBusRouteSearchService.getBusDirections(routeId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 특정 노선의 실시간 버스 위치 및 출발 여부 조회 API
    // GET /api/bus-routes/{routeId}/locations
    @GetMapping("/{routeId}/locations")
    public ResponseEntity<ApiResponse<BusLocationResponseDto>> getBusLocations(
            @PathVariable("routeId") String routeId) {

        if (routeId == null || routeId.trim().isEmpty()) {
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "노선 ID가 유효하지 않습니다.");
        }

        BusLocationResponseDto response = seoulBusLocationService.getRealTimeBusLocations(routeId);

        return ResponseEntity.ok(ApiResponse.success(response));
    }

}

