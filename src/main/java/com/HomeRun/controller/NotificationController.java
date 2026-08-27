package com.HomeRun.controller;

import com.HomeRun.common.response.ApiResponse;
import com.HomeRun.dto.NotificationDto;
import com.HomeRun.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/notifications/arrival")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping
    public ApiResponse<Long> createArrivalNotification(
            Principal principal,
            @RequestBody NotificationDto.CreateArrivalRequest request) {
        
        checkPrincipal(principal);
        Long notificationId = notificationService.createArrivalNotification(principal.getName(), request);
        return ApiResponse.success(notificationId);
    }

    @GetMapping
    public ApiResponse<List<NotificationDto.ArrivalResponse>> getArrivalNotifications(Principal principal) {
        checkPrincipal(principal);
        List<NotificationDto.ArrivalResponse> responses = notificationService.getArrivalNotifications(principal.getName());
        return ApiResponse.success(responses);
    }

    @GetMapping("/{id}")
    public ApiResponse<NotificationDto.ArrivalDetailResponse> getArrivalNotificationDetail(
            Principal principal, @PathVariable Long id) {
        checkPrincipal(principal);
        return ApiResponse.success(notificationService.getArrivalNotificationDetail(principal.getName(), id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<Void> updateArrivalNotification(
            Principal principal,
            @PathVariable Long id,
            @RequestBody NotificationDto.UpdateArrivalRequest request) {
        
        checkPrincipal(principal);
        notificationService.updateArrivalNotification(principal.getName(), id, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping
    public ApiResponse<Void> deleteArrivalNotifications(
            Principal principal,
            @RequestParam List<Long> ids) {
        
        checkPrincipal(principal);
        notificationService.deleteArrivalNotifications(principal.getName(), ids);
        return ApiResponse.success(null);
    }

    @PatchMapping("/{id}/status")
    public ApiResponse<Void> toggleStatus(
            Principal principal,
            @PathVariable Long id,
            @RequestBody NotificationDto.ToggleStatusRequest request) {
        
        checkPrincipal(principal);
        notificationService.toggleStatus(principal.getName(), id, request);
        return ApiResponse.success(null);
    }

    private void checkPrincipal(Principal principal) {
        if (principal == null) {
            throw new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.UNAUTHENTICATED);
        }
    }
}
