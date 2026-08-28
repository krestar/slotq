package com.slotq.auth.dev;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DevCredentialStoreTests {

    @Test
    void aNewBackendProcessStoreInvalidatesPreviousRuntimeCredentials() {
        DevCredentialStore firstProcess = new DevCredentialStore(new SecureRandom());
        DevCredentialStore restartedProcess = new DevCredentialStore(new SecureRandom());

        String previousCredential = firstProcess.credentialFor("customer-a");
        assertThat(restartedProcess.credentialFor("customer-a")).isNotEqualTo(previousCredential);
        assertThat(restartedProcess.resolve(previousCredential)).isEmpty();
    }
}
