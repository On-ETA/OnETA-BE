package com.OnETA.controller;

import com.OnETA.common.response.ApiResponse;
import com.OnETA.dto.TransitDto;
import com.OnETA.service.TransitApiService;
import com.OnETA.service.TransitRouteOptimizationService;
import com.OnETA.entity.NotificationScheduleType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.security.Principal;

@RestController
@RequestMapping("/api/transit")
@RequiredArgsConstructor
public class TransitController {

    private final TransitApiService transitApiService;
    private final TransitRouteOptimizationService transitRouteOptimizationService;

    @GetMapping("/routes/first-last/search")
    public ApiResponse<List<TransitDto.FirstLastRouteOptionResponse>> searchFirstLastRoutes(
            Principal principal,
            @RequestParam Double originX,
            @RequestParam Double originY,
            @RequestParam(required = false) String originAddress,
            @RequestParam(required = false) Double destX,
            @RequestParam(required = false) Double destY,
            @RequestParam(required = false) String destAddress,
            @RequestParam NotificationScheduleType scheduleType) {

        if (principal == null) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.UNAUTHENTICATED);
        }
        if (scheduleType != NotificationScheduleType.FIRST_TRANSIT
                && scheduleType != NotificationScheduleType.LAST_TRANSIT) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.INVALID_INPUT_VALUE,
                    "FIRST_TRANSIT 또는 LAST_TRANSIT을 지정해주세요.");
        }

        return ApiResponse.success(transitRouteOptimizationService.search(
                principal.getName(), originX, originY, originAddress,
                destX, destY, destAddress, scheduleType));
    }

    @GetMapping("/routes/search")
    public ApiResponse<List<TransitDto.RouteOptionResponse>> searchRoutes(
            Principal principal,
            @RequestParam Double originX,
            @RequestParam Double originY,
            @RequestParam(required = false) String originAddress,
            @RequestParam(required = false) Double destX,
            @RequestParam(required = false) Double destY,
            @RequestParam(required = false) String destAddress) {

        if (principal == null) {
            throw new com.OnETA.common.exception.GlobalException(
                    com.OnETA.common.error.ErrorCode.UNAUTHENTICATED);
        }

        List<TransitDto.RouteOptionResponse> responses = transitApiService.searchRoutes(
                principal.getName(), originX, originY, originAddress, destX, destY, destAddress);
        return ApiResponse.success(responses);
    }
}
