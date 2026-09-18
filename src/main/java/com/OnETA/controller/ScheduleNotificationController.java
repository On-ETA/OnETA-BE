package com.OnETA.controller;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.common.response.ApiResponse;
import com.OnETA.dto.NotificationDto;
import com.OnETA.entity.NotificationCategory;
import com.OnETA.entity.NotificationScheduleType;
import com.OnETA.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;

/** Explicit registration/list APIs; existing /arrival endpoints remain compatible. */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class ScheduleNotificationController {
    private final NotificationService service;

    @PostMapping("/schedules")
    public ApiResponse<Long> createSchedule(Principal principal, @RequestBody NotificationDto.CreateArrivalRequest request) {
        String email = email(principal);
        if (request.getScheduleType() != null && request.getScheduleType() != NotificationScheduleType.NORMAL)
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "내 일정 API는 NORMAL만 지원합니다.");
        request.setScheduleType(NotificationScheduleType.NORMAL);
        return ApiResponse.success(service.createArrivalNotification(email, request));
    }

    @PostMapping("/transit")
    public ApiResponse<Long> createTransit(Principal principal, @RequestBody NotificationDto.CreateArrivalRequest request) {
        String email = email(principal);
        if (request.getScheduleType() != NotificationScheduleType.FIRST_TRANSIT
                && request.getScheduleType() != NotificationScheduleType.LAST_TRANSIT)
            throw new GlobalException(ErrorCode.INVALID_INPUT_VALUE, "FIRST_TRANSIT 또는 LAST_TRANSIT을 지정해주세요.");
        request.setTargetArrivalTime(null);
        return ApiResponse.success(service.createArrivalNotification(email, request));
    }

    @GetMapping("/schedules")
    public ApiResponse<List<NotificationDto.ArrivalResponse>> schedules(Principal principal) {
        return ApiResponse.success(service.getArrivalNotifications(email(principal), NotificationCategory.SCHEDULE));
    }

    @GetMapping("/transit")
    public ApiResponse<List<NotificationDto.ArrivalResponse>> transit(Principal principal) {
        return ApiResponse.success(service.getArrivalNotifications(email(principal), NotificationCategory.TRANSIT));
    }

    private String email(Principal principal) {
        if (principal == null) throw new GlobalException(ErrorCode.UNAUTHENTICATED);
        return principal.getName();
    }
}
