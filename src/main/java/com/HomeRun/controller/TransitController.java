package com.HomeRun.controller;

import com.HomeRun.common.response.ApiResponse;
import com.HomeRun.dto.TransitDto;
import com.HomeRun.service.TransitApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/transit")
@RequiredArgsConstructor
public class TransitController {

    private final TransitApiService transitApiService;

    @GetMapping("/routes/search")
    public ApiResponse<List<TransitDto.RouteOptionResponse>> searchRoutes(
            @RequestParam Double originX,
            @RequestParam Double originY,
            @RequestParam Double destX,
            @RequestParam Double destY) {

        if (originX == null || originY == null || destX == null || destY == null) {
            throw new com.HomeRun.common.exception.GlobalException(
                    com.HomeRun.common.error.ErrorCode.INVALID_INPUT_VALUE, "출발지와 목적지의 좌표를 모두 입력해주세요.");
        }

        List<TransitDto.RouteOptionResponse> responses = transitApiService.searchRoutes(originX, originY, destX, destY);
        return ApiResponse.success(responses);
    }
}
