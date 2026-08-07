package com.HomeRun.controller;

import com.HomeRun.common.error.ErrorCode;
import com.HomeRun.common.exception.GlobalException;
import com.HomeRun.common.response.ApiResponse;
import com.HomeRun.dto.MyPageDto;
import com.HomeRun.dto.mypage.*;
import com.HomeRun.service.MyPageService;
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
    public com.HomeRun.common.response.ApiResponse<java.util.List<com.HomeRun.dto.mypage.NoticeListResponseDto>> getNoticeList() {
        return com.HomeRun.common.response.ApiResponse.success(myPageService.getAllNotices());
    }

    @GetMapping("/notices/{id}")
    public com.HomeRun.common.response.ApiResponse<com.HomeRun.dto.mypage.NoticeDetailResponseDto> getNoticeDetail(
            @org.springframework.web.bind.annotation.PathVariable Long id) {
        return com.HomeRun.common.response.ApiResponse.success(myPageService.getNoticeDetail(id));
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
