package com.HomeRun.controller;

import com.HomeRun.common.error.ErrorCode;
import com.HomeRun.common.exception.GlobalException;
import com.HomeRun.common.response.ApiResponse;
import com.HomeRun.dto.UserAddressDto;
import com.HomeRun.service.UserAddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/addresses")
@RequiredArgsConstructor
public class UserAddressController {

    private final UserAddressService userAddressService;

    @PostMapping
    public ApiResponse<UserAddressDto.Response> create(Principal principal,
                                                        @RequestBody UserAddressDto.CreateRequest request) {
        return ApiResponse.success(userAddressService.create(emailOf(principal), request));
    }

    @GetMapping
    public ApiResponse<List<UserAddressDto.Response>> getAll(Principal principal) {
        return ApiResponse.success(userAddressService.getAll(emailOf(principal)));
    }

    @PatchMapping("/{addressId}")
    public ApiResponse<UserAddressDto.Response> update(Principal principal,
                                                        @PathVariable Long addressId,
                                                        @RequestBody UserAddressDto.UpdateRequest request) {
        return ApiResponse.success(userAddressService.update(emailOf(principal), addressId, request));
    }

    @DeleteMapping("/{addressId}")
    public ApiResponse<Void> delete(Principal principal, @PathVariable Long addressId) {
        userAddressService.delete(emailOf(principal), addressId);
        return ApiResponse.success();
    }

    @PutMapping("/{addressId}/current")
    public ApiResponse<UserAddressDto.Response> setCurrent(Principal principal, @PathVariable Long addressId) {
        return ApiResponse.success(userAddressService.setCurrent(emailOf(principal), addressId));
    }

    private String emailOf(Principal principal) {
        if (principal == null) throw new GlobalException(ErrorCode.UNAUTHENTICATED);
        return principal.getName();
    }
}
