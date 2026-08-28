package com.slotq.auth.dev;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.slotq.auth.domain.PrincipalId;

final class DevAuthFixtures {

    private static final Map<String, PrincipalId> FIXTURES = fixtures();

    private DevAuthFixtures() {
    }

    static Map<String, PrincipalId> all() {
        return FIXTURES;
    }

    static Optional<PrincipalId> find(String fixtureKey) {
        return Optional.ofNullable(FIXTURES.get(fixtureKey));
    }

    private static Map<String, PrincipalId> fixtures() {
        LinkedHashMap<String, PrincipalId> fixtures = new LinkedHashMap<>();
        fixtures.put("customer-a", id("10000000-0000-0000-0000-000000000001"));
        fixtures.put("customer-b", id("10000000-0000-0000-0000-000000000002"));
        fixtures.put("tenant-a-owner", id("10000000-0000-0000-0000-000000000003"));
        fixtures.put("tenant-a-manager", id("10000000-0000-0000-0000-000000000004"));
        fixtures.put("tenant-a-staff", id("10000000-0000-0000-0000-000000000005"));
        return Map.copyOf(fixtures);
    }

    private static PrincipalId id(String value) {
        return new PrincipalId(UUID.fromString(value));
    }
}
