package com.OnETA.controller;

import com.OnETA.common.response.ApiResponse;
import com.OnETA.service.FcmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestFcmController {

    private final FcmService fcmService;

    @PostMapping("/fcm")
    public ResponseEntity<ApiResponse<Void>> sendTestPush(
            @RequestParam(name = "title", defaultValue = "테스트 알림") String title,
            @RequestParam(name = "body", defaultValue = "이것은 OnETA 테스트 푸시입니다!") String body,
            Principal principal) {

        // 로그인된 내 계정(이메일)으로 즉시 푸시 발송
        fcmService.sendPush(principal.getName(), title, body);

        return ResponseEntity.ok(ApiResponse.success());
    }
}