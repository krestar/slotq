package com.slotq.mcp;

import com.slotq.ai.flow.*;
import com.slotq.ai.runtime.AgentRuntime;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.databind.JsonNode;
import static com.slotq.ai.runtime.RuntimeContract.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** Deterministic faults against the existing real Auth/MCP/Product/MySQL integration fixture. */
final class AgentLifetimeExperiments {
    enum KillPoint { BEFORE_DISPATCH, IN_PRODUCT_BEFORE_COMMIT, AFTER_COMMIT, AFTER_RESPONSE_LOSS }
    private final RepresentativeFlowIntegrationTests test;

    AgentLifetimeExperiments(RepresentativeFlowIntegrationTests test) {
        this.test = test;
    }

    void terminateAndRestart(KillPoint point) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "agent-lifetime-" + point + "-");
        var config = configuration(directory);
        config.put("killPoint", point.name());
        JsonNode prepared;
        String knownTarget = null;
        long killedPid;
        java.sql.Connection lock = null;
        try (var child = new Child(directory.resolve("original"), config)) {
            prepared = child.checkpoint("prepared");
            assertThat(prepared.path("execution").asString()).isEqualTo("WAITING_CONFIRMATION");
            if (point == KillPoint.IN_PRODUCT_BEFORE_COMMIT) {
                lock = test.db.getDataSource().getConnection();
                lock.setAutoCommit(false);
                try (var statement = lock.prepareStatement("SELECT id FROM slot_inventories WHERE id=? FOR UPDATE")) {
                    statement.setBytes(1, RepresentativeFlowIntegrationTests.bytes(test.slot));
                    try (var rows = statement.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
            }
            if (point != KillPoint.BEFORE_DISPATCH) {
                child.command("dispatch");
            }
            switch (point) {
                case BEFORE_DISPATCH -> assertThat(Files.exists(child.directory.resolve("accepted.json"))).isFalse();
                case IN_PRODUCT_BEFORE_COMMIT -> {
                    child.checkpoint("accepted");
                    assertProductLockWait();
                    assertThat(holds()).isZero();
                }
                case AFTER_COMMIT -> {
                    child.checkpoint("committed");
                    test.assertDurableHold();
                }
                case AFTER_RESPONSE_LOSS -> {
                    var result = child.checkpoint("returned");
                    assertThat(result.path("effect").asString()).isEqualTo("UNKNOWN");
                    assertThat(result.path("invocation").asString()).isEqualTo("RESPONSE_OBSERVED");
                    knownTarget = result.path("knownTarget").asString();
                    assertThat(knownTarget).isNotBlank();
                    test.assertDurableHold();
                }
            }
            killedPid = child.process.pid();
            child.kill();
        } finally {
            if (lock != null) {
                try {
                    lock.rollback();
                } finally {
                    lock.close();
                }
            }
        }
        int expected = point == KillPoint.BEFORE_DISPATCH || point == KillPoint.IN_PRODUCT_BEFORE_COMMIT ? 0 : 1;
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(holds()).isEqualTo(expected));
        assertThat(test.db.queryForObject("SELECT COUNT(*) FROM capacity_allocations WHERE slot_inventory_id=?",
            Integer.class, RepresentativeFlowIntegrationTests.bytes(test.slot))).isEqualTo(expected);
        int ledger = test.db.queryForObject("SELECT COUNT(*) FROM hold_idempotency_records WHERE slot_inventory_id=?",
            Integer.class, RepresentativeFlowIntegrationTests.bytes(test.slot));
        assertThat(ledger).isEqualTo(expected);

        config.put("restart", true);
        config.put("oldRun", prepared.path("runId").asString());
        config.put("knownTarget", knownTarget);
        try (var restarted = new Child(directory.resolve("restart"), config)) {
            assertThat(restarted.process.pid()).isNotEqualTo(killedPid);
            assertThat(restarted.checkpoint("restarted").path("oldRunRejected").asBoolean()).isTrue();
            assertThat(Files.exists(restarted.directory.resolve("accepted.json"))).isFalse();
            assertThat(holds()).isEqualTo(expected);
            restarted.command("recover");
            var recovered = restarted.checkpoint("recovered");
            assertThat(recovered.path("outcome").asString()).isEqualTo(knownTarget == null ? "unknown" : "succeeded");
            if (knownTarget != null) {
                assertThat(recovered.path("tool").asString()).isEqualTo("reservation.get");
            }
            restarted.success();
            assertThat(Files.exists(restarted.directory.resolve("accepted.json"))).isFalse();
        }
        assertThat(holds()).isEqualTo(expected);
        // The old M6 approval may survive, but it is not a recovered Agent Run or retry authority.
        var review = test.approvals.review(test.customer.original().value(), UUID.fromString(prepared.path("intentId").asString()));
        assertThat(review.slotInventoryId()).isEqualTo(test.slot);
        Files.writeString(directory.resolve("summary.json"), test.json.writeValueAsString(Map.of(
            "evidenceKind", "forced-colocated-jvm/deterministic-provider/real-product-mysql", "killPoint", point.name(),
            "killedPid", killedPid, "oldRunRejected", true, "productHttpEntered", point != KillPoint.BEFORE_DISPATCH,
            "durableHolds", holds(), "idempotencyRecords", ledger, "automaticMutationAfterRestart", false,
            "explicitRecovery", knownTarget == null ? "unknown-target-no-dispatch" : "known-target-exact-get")));
    }

    void confirmationWait(int waitSeconds) {
        var runtime = runtime(test::fakeProvider, Duration.ofSeconds(5));
        var flows = new RepresentativeFlows(runtime, test.limits(Duration.ofSeconds(5)));
        var flow = flows.customerHold(test.customer.delegation().value(), test.slot, 2, null);
        var waiting = runtime.generate(flow.id(), test.customer.delegation().value());
        test.approve(runtime, flow, test.customer);
        assertThat(waiting.execution()).isEqualTo(Execution.WAITING_CONFIRMATION);
        assertThat(runtime.activeWorkers()).isZero();
        // A second Run can use the sole worker while the first waits for user re-entry.
        var other = flows.management(test.owner.delegation().value(), test.day, null);
        runtime.generate(other.id(), test.owner.delegation().value());
        assertThat(runtime.activeWorkers()).isZero();
        test.clock.now = test.clock.now.plusSeconds(waitSeconds);
        if (waitSeconds > 600) {
            assertThatThrownBy(() -> runtime.dispatchHold(flow.id(), test.customer.delegation().value())).hasMessage("STATE");
            assertThat(runtime.result(flow.id(), test.customer.delegation().value()).execution()).isEqualTo(Execution.DEADLINE_ENDED);
        } else {
            var result = runtime.dispatchHold(flow.id(), test.customer.delegation().value());
            assertThat(result.productEffect() == Effect.SUCCEEDED).isEqualTo(waitSeconds < 300);
        }
        assertThat(holds()).isEqualTo(waitSeconds < 300 ? 1 : 0);
        assertThat(runtime.result(flow.id(), test.customer.delegation().value()).budget().deadline())
            .isEqualTo(waiting.budget().deadline());
    }

    void saturation(boolean tool) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Provider provider = (model, classification, request, budget) -> {
            if (!tool) {
                entered.countDown();
                waitFor(release);
            }
            return test.fakeProvider(model, classification, request, budget);
        };
        if (tool) doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            entered.countDown();
            waitFor(release);
            return result;
        }).when(test.productClient).exchange(any(), any(), any());
        var runtime = runtime(provider, Duration.ofMillis(250));
        var flows = new RepresentativeFlows(runtime, test.limits(Duration.ofMillis(250)));
        var first = flows.management(test.owner.delegation().value(), test.day, null);
        try {
            if (tool) runtime.readPlanned(first.id(), test.owner.delegation().value(), "management.reservations.list");
            else runtime.generate(first.id(), test.owner.delegation().value());
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(runtime.activeWorkers()).isEqualTo(1);
            var rejected = flows.management(test.owner.delegation().value(), test.day, null);
            assertThatThrownBy(() -> runtime.generate(rejected.id(), test.owner.delegation().value())).hasMessage("ADMISSION");
            runtime.cancel(first.id(), test.owner.delegation().value());
            assertThat(runtime.activeWorkers()).isEqualTo(1);
            assertThatThrownBy(() -> runtime.generate(rejected.id(), test.owner.delegation().value())).hasMessage("ADMISSION");
            assertThat(test.providerCalls).hasValue(0);
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(runtime.activeWorkers()).isZero());
            assertThat(runtime.result(first.id(), test.owner.delegation().value()).execution()).isEqualTo(Execution.CANCEL_REQUESTED);
            int completed = test.providerCalls.get();
            assertThat(completed).isEqualTo(tool ? 0 : 1);
            assertThat(runtime.result(rejected.id(), test.owner.delegation().value()).budget().providerAttempts()).isZero();
            // Rejection did not enqueue a hidden provider attempt. Only explicit re-entry dispatches it.
            runtime.generate(rejected.id(), test.owner.delegation().value());
            assertThat(test.providerCalls).hasValue(completed + 1);
        } finally {
            release.countDown();
        }
    }

    private AgentRuntime runtime(Provider provider, Duration timeout) {
        var runtime = new AgentRuntime(test.access, test.registry, test.engine, test.approvals, provider,
            RepresentativeRouterProfile.candidates(), new RepresentativeContext(actor -> true),
            new LocalAdmission(new LocalAdmission.Limit(200, 200, 1), 128, System::nanoTime), test.clock, 16, 1);
        test.runtimes.add(runtime);
        return runtime;
    }

    private int holds() {
        return test.db.queryForObject("SELECT COUNT(*) FROM reservations WHERE slot_inventory_id=?", Integer.class,
            RepresentativeFlowIntegrationTests.bytes(test.slot));
    }

    private void assertProductLockWait() throws Exception {
        var mysql = RepresentativeFlowIntegrationTests.MYSQL;
        try (var observer = java.sql.DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                var query = observer.prepareStatement("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='slot_inventories'
                    """)) {
            query.setString(1, mysql.getDatabaseName());
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isPositive();
                }
            });
        }
    }

    private Map<String,Object> configuration(Path directory) {
        var config = new LinkedHashMap<String,Object>();
        config.put("directory", directory.toString());
        config.put("jdbcUrl", RepresentativeFlowIntegrationTests.MYSQL.getJdbcUrl());
        config.put("jdbcUser", RepresentativeFlowIntegrationTests.MYSQL.getUsername());
        config.put("jdbcPassword", RepresentativeFlowIntegrationTests.MYSQL.getPassword());
        config.put("venue", test.venue.value().toString());
        config.put("keyStore", McpHttpIntegrationTests.KEYSTORE.toUri().toString());
        config.put("keyStorePassword", McpHttpIntegrationTests.PASSWORD);
        config.put("delegation", test.customer.delegation().value());
        config.put("original", test.customer.original().value());
        config.put("slot", test.slot.toString());
        return config;
    }

    private static void waitFor(CountDownLatch release) {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Continuing work gate not released");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Continuing work interrupted");
        }
    }

    private final class Child implements AutoCloseable {
        private final Path directory;
        private final Process process;
        private final BufferedWriter input;

        Child(Path directory, Map<String,Object> config) throws Exception {
            this.directory = Files.createDirectories(directory);
            Path arguments = directory.resolve("java.args");
            String classpath = System.getProperty("slotq.test.runtime-classpath").replace('\\', '/');
            Files.writeString(arguments, "-Djdk.httpclient.disableRetryConnect=true\n-Djdk.httpclient.enableAllMethodRetry=false\n"
                + "-Djdk.httpclient.redirects.retrylimit=1\n-cp\n\"" + classpath + "\"\n" + AgentLifetimeProcessProbe.class.getName());
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments)
                .redirectErrorStream(true).redirectOutput(directory.resolve("process.log").toFile()).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            var childConfig = new LinkedHashMap<>(config);
            childConfig.put("directory", directory.toString());
            int port = McpHttpIntegrationTests.port();
            childConfig.put("port", port);
            childConfig.put("origin", "https://localhost:" + port);
            command(test.json.writeValueAsString(childConfig));
        }

        void command(String command) throws IOException {
            input.write(command);
            input.newLine();
            input.flush();
        }

        JsonNode checkpoint(String name) throws Exception {
            Path file = directory.resolve(name + ".json");
            await("child checkpoint " + name + ": " + directory).atMost(Duration.ofSeconds(60)).until(() -> {
                if (!process.isAlive() && !Files.exists(file)) {
                    throw new AssertionError("Child exited; inspect " + directory.resolve("process.log"));
                }
                return Files.exists(file);
            });
            return test.json.readTree(Files.readString(file));
        }

        void kill() throws Exception {
            assertThat(process.isAlive()).isTrue();
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
        }

        void success() throws Exception {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        }

        @Override public void close() throws Exception {
            if (process.isAlive()) {
                process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Agent child did not exit");
                }
            }
            input.close();
        }
    }
}
