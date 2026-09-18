package com.OnETA.controller;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.common.response.ApiResponse;
import com.OnETA.service.PushTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class PushTestController {
    private final PushTestService service;

    @PostMapping("/test")
    public ApiResponse<String> test(Principal principal) {
        if (principal == null) throw new GlobalException(ErrorCode.UNAUTHENTICATED);
        return ApiResponse.success(service.send(principal.getName()));
    }
}
