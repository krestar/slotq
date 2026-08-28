package com.slotq.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("slotq.cors")
public record SlotqCorsProperties(List<String> allowedOrigins) {

    public SlotqCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
