package com.HomeRun.service;

import java.time.Duration;

public class FcmPushException extends RuntimeException {

    private final String errorCode;
    private final boolean permanent;
    private final Duration retryAfter;

    public FcmPushException(String errorCode, String message, boolean permanent, Throwable cause) {
        this(errorCode, message, permanent, null, cause);
    }

    public FcmPushException(String errorCode, String message, boolean permanent,
                            Duration retryAfter, Throwable cause) {
        super(message == null ? "FCM 푸시 발송에 실패했습니다." : message, cause);
        this.errorCode = errorCode;
        this.permanent = permanent;
        this.retryAfter = retryAfter;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isPermanent() {
        return permanent;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public boolean isQuotaExceeded() {
        return "QUOTA_EXCEEDED".equals(errorCode);
    }
}
