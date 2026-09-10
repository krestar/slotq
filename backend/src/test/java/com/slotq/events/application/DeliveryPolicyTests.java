package com.slotq.events.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryPolicyTests {
    private static final List<Duration> DEFAULT_DELAYS = List.of(
        Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2));

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withInitializer(application -> application.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()))
        .withBean(DeliveryPolicy.class);

    @Test
    void productionDefaultsBindAndApplyOneBoundedDelayForEachRemainingAttempt() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            DeliveryPolicy policy = application.getBean(DeliveryPolicy.class);
            assertThat(policy.maxAttempts()).isEqualTo(5);
            assertThat(policy.lease()).isEqualTo(Duration.ofSeconds(30));
            assertThat(policy.effectTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(policy.lockWait()).isEqualTo(Duration.ofSeconds(5));
            assertThat(policy.batchSize()).isEqualTo(100);
            for (int attempt = 1; attempt < policy.maxAttempts(); attempt++) {
                assertThat(policy.retryDelay(attempt)).isEqualTo(DEFAULT_DELAYS.get(attempt - 1));
            }
            assertThatThrownBy(() -> policy.retryDelay(0)).isInstanceOf(IndexOutOfBoundsException.class);
            assertThatThrownBy(() -> policy.retryDelay(5)).isInstanceOf(IndexOutOfBoundsException.class);
        });
    }

    @Test
    void invalidConfigurationFailsDuringContextActivation() {
        context.withPropertyValues("slotq.events.delivery.lease=PT19S")
            .run(application -> assertThat(application).hasFailed());
    }

    @Test
    void acceptsTheExactLeaseSafetyBoundaryAndASingleAttemptWithoutRetries() {
        DeliveryPolicy policy = new DeliveryPolicy(1, Duration.ofSeconds(4), Duration.ofSeconds(2),
            Duration.ofSeconds(1), 1, List.of());

        assertThat(policy.maxAttempts()).isEqualTo(1);
        assertThat(policy.retryDelays()).isEmpty();
        assertThat(policy.lease()).isEqualTo(policy.effectTimeout().multipliedBy(2));
    }

    @Test
    void acceptsFiniteUpperBoundsAndZeroDelayTestOverrides() {
        DeliveryPolicy policy = new DeliveryPolicy(100, Duration.ofDays(1), Duration.ofHours(1),
            Duration.ofSeconds(3599), 100, Collections.nCopies(99, Duration.ofDays(1)));
        assertThat(policy.retryDelay(99)).isEqualTo(Duration.ofDays(1));
        assertThat(new DeliveryPolicy(2, Duration.ofSeconds(4), Duration.ofSeconds(2),
            Duration.ofSeconds(1), 1, List.of(Duration.ZERO)).retryDelay(1)).isZero();
    }

    @Test
    void keepsAnImmutableCopyOfRetryConfiguration() {
        var supplied = new ArrayList<>(DEFAULT_DELAYS);
        DeliveryPolicy policy = policy(5, Duration.ofSeconds(30), Duration.ofSeconds(10),
            Duration.ofSeconds(5), 100, supplied);
        supplied.set(0, Duration.ofHours(1));

        assertThat(policy.retryDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThatThrownBy(() -> policy.retryDelays().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void rejectsInvalidBudgetsTimeoutRelationsAndUnrepresentableJdbcTimeouts(String description, Runnable create) {
        assertThatThrownBy(create::run).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidConfigurations() {
        Duration lease = Duration.ofSeconds(30);
        Duration effect = Duration.ofSeconds(10);
        Duration lock = Duration.ofSeconds(5);
        return Stream.of(
            invalid("zero attempts", () -> policy(0, lease, effect, lock, 100, DEFAULT_DELAYS)),
            invalid("excess attempts", () -> policy(101, lease, effect, lock, 100, DEFAULT_DELAYS)),
            invalid("zero batch", () -> policy(5, lease, effect, lock, 0, DEFAULT_DELAYS)),
            invalid("excess batch", () -> policy(5, lease, effect, lock, 101, DEFAULT_DELAYS)),
            invalid("null lease", () -> policy(5, null, effect, lock, 100, DEFAULT_DELAYS)),
            invalid("null effect", () -> policy(5, lease, null, lock, 100, DEFAULT_DELAYS)),
            invalid("null lock", () -> policy(5, lease, effect, null, 100, DEFAULT_DELAYS)),
            invalid("zero lock", () -> policy(5, lease, effect, Duration.ZERO, 100, DEFAULT_DELAYS)),
            invalid("negative lock", () -> policy(5, lease, effect, Duration.ofSeconds(-1), 100, DEFAULT_DELAYS)),
            invalid("fractional lock", () -> policy(5, lease, effect, Duration.ofMillis(500), 100, DEFAULT_DELAYS)),
            invalid("fractional effect", () -> policy(5, lease, Duration.ofMillis(9500), lock, 100, DEFAULT_DELAYS)),
            invalid("equal lock and effect", () -> policy(5, lease, lock, lock, 100, DEFAULT_DELAYS)),
            invalid("lock longer than effect", () -> policy(5, lease, Duration.ofSeconds(4), lock, 100, DEFAULT_DELAYS)),
            invalid("insufficient lease", () -> policy(5, Duration.ofSeconds(19), effect, lock, 100, DEFAULT_DELAYS)),
            invalid("excess lease", () -> policy(5, Duration.ofDays(1).plusSeconds(1), effect, lock, 100, DEFAULT_DELAYS)),
            invalid("excess effect", () -> policy(5, Duration.ofDays(1), Duration.ofHours(1).plusSeconds(1), lock, 100, DEFAULT_DELAYS)),
            invalid("missing delays", () -> policy(5, lease, effect, lock, 100, null)),
            invalid("short delay schedule", () -> policy(5, lease, effect, lock, 100, List.of(Duration.ZERO))),
            invalid("long delay schedule", () -> policy(5, lease, effect, lock, 100, Collections.nCopies(5, Duration.ZERO))),
            invalid("null delay", () -> policy(5, lease, effect, lock, 100,
                Arrays.asList(Duration.ZERO, null, Duration.ZERO, Duration.ZERO))),
            invalid("negative delay", () -> policy(5, lease, effect, lock, 100,
                Collections.nCopies(4, Duration.ofSeconds(-1)))),
            invalid("unbounded delay", () -> policy(5, lease, effect, lock, 100,
                Collections.nCopies(4, Duration.ofDays(1).plusNanos(1))))
        );
    }

    private static Arguments invalid(String description, Runnable create) {
        return Arguments.of(description, create);
    }

    private static DeliveryPolicy policy(int attempts, Duration lease, Duration effect, Duration lock,
                                         int batch, List<Duration> delays) {
        return new DeliveryPolicy(attempts, lease, effect, lock, batch, delays);
    }
}
