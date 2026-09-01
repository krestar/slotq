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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
    "slotq.auth.dev-bootstrap-enabled=true"
})
@ActiveProfiles("test")
class ConcurrencyBaselineSmokeTests {

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
        .withDatabaseName("slotq");

    @Autowired ConfigurableApplicationContext context;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @LocalServerPort int serverPort;

    @Test
    void runsTheRealKeylessProductHoldPathAndProducesTheStableCorrectnessSchema() throws Exception {
        BaselineReport report = ConcurrencyBaselineRunner.execute(
            context,
            URI.create("http://127.0.0.1:" + serverPort),
            new ConcurrencyBaselineConfig(
                2, 2, 15001L, 2, Duration.ofMinutes(5), Duration.ofSeconds(10),
                Path.of("build/reports/experiments/smoke-unused.json")
            )
        );

        assertThat(report.schemaVersion()).isEqualTo("slotq-concurrency-baseline/v1");
        assertThat(report.productModel().slotCapacity()).isEqualTo(1);
        assertThat(report.productModel().allocationUnit()).isEqualTo(1);
        assertThat(report.workload().seed()).isEqualTo(15001L);
        assertThat(report.requests()).hasSize(4);
        assertThat(report.metrics().totalRequests()).isEqualTo(4);
        assertThat(report.metrics().successfulHoldResultCount()).isPositive();
        assertThat(report.metrics().successfulHoldResultCount()
            + report.metrics().businessConflictCount()
            + report.metrics().systemFailureCount()
            + report.metrics().timeoutCount()).isEqualTo(report.metrics().totalRequests());
        assertThat(report.slotObservations()).hasSize(2);
        assertThat(report.slotObservations())
            .extracting(ConcurrencyBaselineRunner.SlotObservation::verificationNow)
            .containsOnly(report.verificationNow());
        assertThat(report.slotObservations())
            .allSatisfy(observation -> {
                assertThat(observation.effectiveOccupancy()).isGreaterThanOrEqualTo(0);
                assertThat(observation.rawActiveAllocationRows()).isGreaterThanOrEqualTo(0);
            });
        assertThat(report.metrics().effectiveOccupancy()).isEqualTo(report.slotObservations().stream()
            .mapToInt(ConcurrencyBaselineRunner.SlotObservation::effectiveOccupancy).sum());
        assertThat(report.metrics().rawActiveAllocationRows()).isEqualTo(report.slotObservations().stream()
            .mapToInt(ConcurrencyBaselineRunner.SlotObservation::rawActiveAllocationRows).sum());
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(report))
            .path("schemaVersion").asText()).isEqualTo("slotq-concurrency-baseline/v1");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM hold_idempotency_records", Long.class
        )).isZero();
    }
}
