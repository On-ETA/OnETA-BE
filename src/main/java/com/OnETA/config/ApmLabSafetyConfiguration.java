package com.OnETA.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fail before ordinary beans (including Flyway and DataSource) are initialized
 * if someone points the APM lab at a non-lab database.
 */
@Configuration(proxyBeanMethods = false)
@Profile("apm")
public class ApmLabSafetyConfiguration {
    private static final String LOCAL_JDBC = "jdbc:mysql://mysql:3306/oneta_apm";

    @Bean
    static BeanFactoryPostProcessor enforceApmLabIsolation(Environment environment) {
        return beanFactory -> {
            String[] profiles = environment.getActiveProfiles();
            if (profiles.length != 1 || !"apm".equals(profiles[0])) {
                throw new IllegalStateException("APM lab requires the apm profile only");
            }

            String jdbcUrl = environment.getProperty("spring.datasource.url", "");
            if (!jdbcUrl.equals(LOCAL_JDBC) && !jdbcUrl.startsWith(LOCAL_JDBC + "?")) {
                throw new IllegalStateException("APM lab refuses a non-local Docker MySQL URL");
            }
            if (!"oneta_lab".equals(environment.getProperty("spring.datasource.username"))) {
                throw new IllegalStateException("APM lab requires its dedicated database user");
            }
            if (!"redis".equals(environment.getProperty("spring.data.redis.host"))) {
                throw new IllegalStateException("APM lab requires its isolated Docker Redis");
            }
            if (environment.getProperty("app.firebase.enabled", Boolean.class, true)) {
                throw new IllegalStateException("FCM must be disabled in the APM lab");
            }

            String jwtSecret = environment.getProperty("jwt.secret", "");
            if (jwtSecret.length() < 32 || jwtSecret.contains("REPLACE")) {
                throw new IllegalStateException("Generate a local-only JWT secret with perf/prepare.ps1");
            }
        };
    }
}
