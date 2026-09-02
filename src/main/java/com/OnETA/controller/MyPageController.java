package com.OnETA.controller;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.common.response.ApiResponse;
import com.OnETA.dto.MyPageDto;
import com.OnETA.dto.mypage.*;
import com.OnETA.service.MyPageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/mypage")
@RequiredArgsConstructor
public class MyPageController {

    private final MyPageService myPageService;

    @GetMapping
    public MyPageDto.Response getMyPageInfo(Principal principal) {
        if (principal == null) {
            throw new IllegalArgumentException("인증 정보가 없습니다.");
        }
        String email = principal.getName();
        return myPageService.getMyPageInfo(email);
    }

    @GetMapping("/notices")
    public com.OnETA.common.response.ApiResponse<java.util.List<com.OnETA.dto.mypage.NoticeListResponseDto>> getNoticeList() {
        return com.OnETA.common.response.ApiResponse.success(myPageService.getAllNotices());
    }

    @GetMapping("/notices/{id}")
    public com.OnETA.common.response.ApiResponse<com.OnETA.dto.mypage.NoticeDetailResponseDto> getNoticeDetail(
            @org.springframework.web.bind.annotation.PathVariable Long id) {
        return com.OnETA.common.response.ApiResponse.success(myPageService.getNoticeDetail(id));
    }

    @PatchMapping("/nickname")
    public ResponseEntity<ApiResponse<Void>> updateNickname(
            @Valid @RequestBody NicknameUpdateRequestDto request,
            Principal principal){ // 현재 로그인한 사용자 정보

        if (principal == null){
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "인증 정보가 없습니다.");
        }

        myPageService.updateNickname(principal.getName(), request);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @PatchMapping("/password")
    public ResponseEntity<ApiResponse<Void>> updatePassword(
            @Valid @RequestBody PasswordUpdateRequestDto request,
            Principal principal){ // 현재 로그인한 사용자 정보

        if (principal == null) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "인증 정보가 없습니다.");
        }

        myPageService.updatePassword(principal.getName(), request);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/inquiry")
    public ResponseEntity<ApiResponse<Void>> submitInquiry(
            @Valid @RequestBody InquiryRequestDto request,
            Principal principal){ // 현재 로그인한 사용자 정보

        if (principal == null) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "인증 정보가 없습니다.");
        }

        myPageService.sendInquiry(principal.getName(), request);

        return ResponseEntity.ok(ApiResponse.success());
    }

}
