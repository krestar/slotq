package com.slotq.auth.dev;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.slotq.auth.domain.AuthenticatedPrincipal;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.web.BearerCredentialResolver;

final class DevCredentialStore implements BearerCredentialResolver {

    private static final int TOKEN_BYTES = 32;

    private final Map<String, String> tokenByFixture;
    private final Map<String, PrincipalId> principalByToken;

    DevCredentialStore(SecureRandom secureRandom) {
        Map<String, String> tokens = new HashMap<>();
        Map<String, PrincipalId> principals = new HashMap<>();
        DevAuthFixtures.all().forEach((fixtureKey, principalId) -> {
            byte[] random = new byte[TOKEN_BYTES];
            secureRandom.nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            tokens.put(fixtureKey, token);
            principals.put(token, principalId);
        });
        tokenByFixture = Map.copyOf(tokens);
        principalByToken = Map.copyOf(principals);
    }

    String credentialFor(String fixtureKey) {
        String credential = tokenByFixture.get(fixtureKey);
        if (credential == null) {
            throw new UnknownFixtureException();
        }
        return credential;
    }

    @Override
    public Optional<AuthenticatedPrincipal> resolve(String credential) {
        return Optional.ofNullable(principalByToken.get(credential))
            .map(AuthenticatedPrincipal::new);
    }
}
