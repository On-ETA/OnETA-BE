package com.OnETA.config;

import com.OnETA.common.ExternalApiCallCounter;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ExternalApiQuotaConfig {
    public ExternalApiQuotaConfig(Environment environment) {
        ExternalApiCallCounter.configureLimits(name ->
                environment.getProperty("API_DAILY_LIMIT_" + name, Long.class));
    }
}
