package com.OnETA.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Preserve scheduling in existing environments, but never register scheduled
 * tasks when the isolated performance-lab profile is active.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!apm")
@EnableScheduling
public class SchedulingConfiguration {
}
