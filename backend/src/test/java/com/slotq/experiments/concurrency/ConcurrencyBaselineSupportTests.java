package com.slotq.experiments.concurrency;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConcurrencyBaselineSupportTests {

    @Test
    void barrierDoesNotReleaseAnyClientBeforeEveryParticipantArrives() throws Exception {
        int clients = 4;
        CyclicBarrier barrier = new CyclicBarrier(clients + 1);
        AtomicInteger ready = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(clients)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int client = 0; client < clients; client++) {
                futures.add(executor.submit(() -> {
                    ready.incrementAndGet();
                    barrier.await(2, TimeUnit.SECONDS);
                    started.incrementAndGet();
                    return null;
                }));
            }
            while (ready.get() < clients) {
                Thread.onSpinWait();
            }
            assertThat(started).hasValue(0);

            barrier.await(2, TimeUnit.SECONDS);
            for (Future<?> future : futures) {
                future.get(2, TimeUnit.SECONDS);
            }
            assertThat(started).hasValue(clients);
        }
    }

    @Test
    void configurationRejectsValuesOutsideTheProductWorkloadContract() {
        assertThatThrownBy(() -> new ConcurrencyBaselineConfig(
            1, 1, 15L, 2, Duration.ofMinutes(5), Duration.ofSeconds(10), Path.of("result.json")
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConcurrencyBaselineConfig(
            2, 1, 15L, 2, Duration.ofSeconds(90), Duration.ofSeconds(10), Path.of("result.json")
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
