package com.slotq.auth.dev;

import java.security.SecureRandom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local | test")
@ConditionalOnProperty(name = "slotq.auth.dev-bootstrap-enabled", havingValue = "true")
class DevAuthConfiguration {

    @Bean
    DevCredentialStore devCredentialStore() {
        return new DevCredentialStore(new SecureRandom());
    }
}
