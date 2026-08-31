package com.HomeRun.controller;

import com.HomeRun.common.response.ApiResponse;
import com.HomeRun.dto.DeviceTokenDto;
import com.HomeRun.service.DeviceTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

@RestController
@RequestMapping("/api/notifications/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    @PostMapping
    public ApiResponse<Void> registerToken(
            Principal principal,
            @RequestBody DeviceTokenDto.RegisterRequest request) {
        
        if (principal == null) {
            throw new com.HomeRun.common.exception.GlobalException(com.HomeRun.common.error.ErrorCode.UNAUTHENTICATED);
        }
        
        deviceTokenService.registerOrUpdateToken(principal.getName(), request.getDeviceToken());
        return ApiResponse.success(null);
    }
}
