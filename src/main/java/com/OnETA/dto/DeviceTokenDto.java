package com.HomeRun.dto;

import lombok.Getter;
import lombok.Setter;

public class DeviceTokenDto {

    @Getter
    @Setter
    public static class RegisterRequest {
        private String deviceToken;
    }
}
