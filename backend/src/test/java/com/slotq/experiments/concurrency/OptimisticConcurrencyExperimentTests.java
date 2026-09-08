package com.slotq.experiments.concurrency;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import com.slotq.experiments.concurrency.ConcurrencyBaselineRunner.BaselineReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "slotq.auth.dev-bootstrap-enabled=true"
})
@ActiveProfiles({"test", "optimistic-concurrency-experiment"})
class OptimisticConcurrencyExperimentTests {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("slotq_optimistic_experiment");

    @Autowired ConfigurableApplicationContext context;
    @LocalServerPort int serverPort;

    @Test
    void keepsTheOptimisticCandidateOutsideProductionAndPreservesCapacity() throws Exception {
        BaselineReport report = ConcurrencyBaselineRunner.execute(
            context,
            URI.create("http://127.0.0.1:" + serverPort),
            new ConcurrencyBaselineConfig(
                10, 3, 15001L, 2, Duration.ofMinutes(5), Duration.ofSeconds(10),
                ConcurrencyStrategy.OPTIMISTIC_SLOT_VERSION_BOUNDED_RETRY,
                Path.of("build/reports/experiments/optimistic-smoke-unused.json")
            )
        );

        assertThat(report.productModel().concurrencyStrategy())
            .isEqualTo("OPTIMISTIC_SLOT_VERSION_BOUNDED_RETRY");
        assertThat(report.metrics().totalRequests()).isEqualTo(30);
        assertThat(report.metrics().successfulHoldResultCount()).isEqualTo(3);
        assertThat(report.metrics().businessConflictCount()).isEqualTo(27);
        assertThat(report.metrics().systemFailureCount()).isZero();
        assertThat(report.metrics().timeoutCount()).isZero();
        assertThat(report.metrics().invariantViolationCount()).isZero();
        assertThat(report.metrics().partialCommitCount()).isZero();
        assertThat(report.metrics().effectiveOccupancy()).isEqualTo(3);
        assertThat(report.strategyCountersAfter().staleRetries())
            .isEqualTo(report.metrics().staleRetryCount());
        assertThat(report.metrics().staleRetryExhaustionCount()).isZero();
    }
}
