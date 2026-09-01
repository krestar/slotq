package com.slotq.experiments.concurrency;

import java.nio.file.Path;
import java.time.Duration;

record ConcurrencyBaselineConfig(
    int clients,
    int iterations,
    long seed,
    int partySize,
    Duration holdDuration,
    Duration timeout,
    Path output
) {

    private static final String PREFIX = "slotq.baseline.";

    static ConcurrencyBaselineConfig fromSystemProperties() {
        return new ConcurrencyBaselineConfig(
            integer("clients", 10),
            integer("iterations", 20),
            Long.parseLong(property("seed", "15001")),
            integer("partySize", 2),
            Duration.parse(property("holdDuration", "PT5M")),
            Duration.parse(property("timeout", "PT10S")),
            Path.of(property(
                "output", "build/reports/experiments/concurrency-baseline.json"
            ))
        );
    }

    ConcurrencyBaselineConfig {
        if (clients < 2) {
            throw new IllegalArgumentException("clients must be at least 2");
        }
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be positive");
        }
        if (partySize < 1) {
            throw new IllegalArgumentException("partySize must be positive");
        }
        requirePositive("holdDuration", holdDuration);
        requirePositive("timeout", timeout);
        if (holdDuration.toSecondsPart() != 0 || holdDuration.toNanosPart() != 0) {
            throw new IllegalArgumentException("holdDuration must use whole minutes");
        }
        if (holdDuration.toMinutes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("holdDuration is too large");
        }
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
    }

    int holdDurationMinutes() {
        return Math.toIntExact(holdDuration.toMinutes());
    }

    private static int integer(String name, int defaultValue) {
        return Integer.parseInt(property(name, Integer.toString(defaultValue)));
    }

    private static String property(String name, String defaultValue) {
        return System.getProperty(PREFIX + name, defaultValue);
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
