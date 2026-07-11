package com.HomeRun.controller;

import com.HomeRun.common.error.ErrorCode;
import com.HomeRun.common.exception.GlobalException;
import com.HomeRun.common.response.ApiResponse;
import com.HomeRun.dto.auth.*;
import com.HomeRun.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.security.Principal;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final EmailService emailService;

    // POST /api/auth/signup (회원가입)
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<TokenResponseDto>> signup(@Valid @RequestBody SignupRequestDto request) {

        TokenResponseDto tokenResponse = authService.signup(request);

        return ResponseEntity
                .status(HttpStatus.CREATED) // 201 Created
                .body(ApiResponse.success(tokenResponse));

    }

    // POST /api/auth/signup/consent
    @PostMapping("/signup/consent")
    public ResponseEntity<ApiResponse<TokenResponseDto>> processConsent(@RequestBody ConsentRequestDto request, Principal principal) {

        // 토큰 없이 접근했거나, 잘못된 토큰인 경우
        if (principal == null) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "인증되지 않은 사용자입니다.");
        }

        String email = principal.getName();
        TokenResponseDto tokenResponse = authService.processConsent(email, request);

        return ResponseEntity.ok(ApiResponse.success(tokenResponse));

    }

    // POST /api/auth/login (로그인)
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponseDto>> login(@RequestBody LoginRequestDto request) {

        TokenResponseDto token = authService.login(request);

        return ResponseEntity.ok(ApiResponse.success(token));
    }

    // 토큰 재발급 API (만료되었을 때 모바일 앱이 호출)
    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<TokenResponseDto>> reissue(@RequestBody ReissueRequestDto request) {

        TokenResponseDto tokenResponse = authService.reissueToken(request.getRefreshToken());

        return ResponseEntity.ok(ApiResponse.success(tokenResponse));
    }

    @PostMapping("/email/send")
    public ResponseEntity<ApiResponse<Void>> sendVerificationEmail(@RequestBody EmailSendRequestDto request) {

        emailService.sendVerificationCode(request.getEmail());

        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/email/verify")
    public ResponseEntity<ApiResponse<Void>> verifyEmailCode(@RequestBody EmailVerifyRequestDto request) {

        emailService.verifyCode(request.getEmail(), request.getCode());

        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody PasswordResetRequestDto request){

        authService.resetPassword(request);

        return ResponseEntity.ok(ApiResponse.success());
    }

}