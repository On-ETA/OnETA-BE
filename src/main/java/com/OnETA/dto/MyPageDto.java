package com.OnETA.dto;

import lombok.Builder;
import lombok.Getter;

public class MyPageDto {

    @Getter
    @Builder
    public static class Response {
        private String nickname;
        private String email;
        private String appVersion;
    }
}
