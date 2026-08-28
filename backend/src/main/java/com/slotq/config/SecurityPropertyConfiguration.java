package com.slotq.config;

import java.util.Arrays;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(SlotqCorsProperties.class)
class SecurityPropertyConfiguration implements InitializingBean {

    private final Environment environment;
    private final SlotqCorsProperties corsProperties;

    SecurityPropertyConfiguration(Environment environment, SlotqCorsProperties corsProperties) {
        this.environment = environment;
        this.corsProperties = corsProperties;
    }

    @Override
    public void afterPropertiesSet() {
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("production");
        boolean devBootstrap = environment.getProperty("slotq.auth.dev-bootstrap-enabled", Boolean.class, false);
        if (production && devBootstrap) {
            throw new IllegalStateException("Development auth bootstrap cannot be enabled in production");
        }
        if (production && corsProperties.allowedOrigins().contains("*")) {
            throw new IllegalStateException("Wildcard CORS origin is not allowed in production");
        }
    }
}
