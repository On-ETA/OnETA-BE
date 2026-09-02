package com.OnETA.controller;

import com.OnETA.common.error.ErrorCode;
import com.OnETA.common.exception.GlobalException;
import com.OnETA.common.response.ApiResponse;
import com.OnETA.dto.bus.DepotNotificationRequestDto;
import com.OnETA.dto.bus.DepotNotificationResponseDto;
import com.OnETA.entity.BusDirection;
import com.OnETA.service.DepotNotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

// DepotNotification 등록, 조회, 삭제 컨트롤러
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class DepotNotificationController {

    private final DepotNotificationService depotNotificationService;

    @GetMapping("/depot/my")
    public ResponseEntity<ApiResponse<List<DepotNotificationResponseDto>>> getMyDepotNotifications(
            Principal principal) {

        if (principal == null) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "로그인이 필요합니다.");
        }

        List<DepotNotificationResponseDto> response = depotNotificationService.getMyDepotNotifications(principal.getName());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/depot")
    public ResponseEntity<ApiResponse<Void>> setDepotNotification(
            @Valid @RequestBody DepotNotificationRequestDto request,
            Principal principal) {

        if (principal == null) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "로그인이 필요합니다.");
        }

        // UserBus 등록 및 Depot알림 활성화 동시 처리
        depotNotificationService.setDepotNotification(principal.getName(), request);

        return ResponseEntity.ok(ApiResponse.success());
    }

    // PK 값을 Path Variable로 받아서 삭제
    @DeleteMapping("/depot/{userBusId}")
    public ResponseEntity<ApiResponse<Void>> deleteDepotNotification(
            @PathVariable("userBusId") Long userBusId,
            Principal principal) {

        if (principal == null) {
            throw new GlobalException(ErrorCode.HANDLE_ACCESS_DENIED, "로그인이 필요합니다.");
        }

        depotNotificationService.deleteDepotNotification(principal.getName(), userBusId);

        return ResponseEntity.ok(ApiResponse.success());
    }
}