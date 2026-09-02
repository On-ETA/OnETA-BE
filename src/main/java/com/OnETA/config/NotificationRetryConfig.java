package com.OnETA.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Configuration
@EnableConfigurationProperties(NotificationRetryProperties.class)
public class NotificationRetryConfig {

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService notificationRetryExecutor() {
        return Executors.newScheduledThreadPool(4);
    }

    @Bean
    public Semaphore notificationFcmSemaphore(NotificationRetryProperties properties) {
        return new Semaphore(properties.getMaxConcurrentFcm());
    }
}
