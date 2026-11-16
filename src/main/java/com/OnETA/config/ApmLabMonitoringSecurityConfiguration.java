package com.OnETA.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permits Prometheus scraping only on the Docker-isolated management HTTP
 * listener. The Nginx proxy NEVER exposes port 9091, and the app has
 * NO non-internal Docker networks.
 */
@Configuration(proxyBeanMethods = false)
@Profile("apm")
public class ApmLabMonitoringSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain apmManagementEndpoints(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
