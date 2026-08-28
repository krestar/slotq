package com.slotq.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityPropertyConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SecurityPropertyConfiguration.class);

    @Test
    void productionStartupFailsWhenDevBootstrapIsEnabled() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=production",
                "slotq.auth.dev-bootstrap-enabled=true",
                "slotq.cors.allowed-origins=https://slotq.example"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("Development auth bootstrap cannot be enabled in production");
            });
    }

    @Test
    void productionStartupRejectsWildcardCors() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=production",
                "slotq.auth.dev-bootstrap-enabled=false",
                "slotq.cors.allowed-origins=*"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("Wildcard CORS origin is not allowed in production");
            });
    }
}
