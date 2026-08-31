package com.HomeRun.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "notification.delivery.retry")
public class NotificationRetryProperties {

    private Duration exponentialBaseDelay = Duration.ofSeconds(10);
    private Duration maxBackoff = Duration.ofMinutes(1);
    private Duration quotaFallbackDelay = Duration.ofMinutes(1);
    private double jitterRatio = 0.2;
    private int maxConcurrentFcm = 4;
    private Duration concurrencyRetryDelay = Duration.ofSeconds(1);
}
