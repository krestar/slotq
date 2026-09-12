package com.slotq.experiments.events;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.SpringBootVersion;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/** Small decision experiment, not a production worker or crash/restart framework. */
public final class EventExperimentRunner {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            try (var fixture = new EventFixture(System.getenv("EVENT_DB_URL"),
                System.getenv("EVENT_DB_USER"), System.getenv("EVENT_DB_PASSWORD"))) {
                if (args[0].equals("RECOVER")) {
                    for (var event : fixture.storedEvents()) {
                        fixture.tx.executeWithoutResult(status -> fixture.effect(event, "NONE"));
                    }
                } else if (args[0].equals("DELIVER")) {
                    var event = fixture.storedEvents().stream()
                        .filter(e -> e.eventId().equals(fixture.event(Long.parseLong(args[1]), 1).eventId()))
                        .findFirst().orElseThrow();
                    var delivery = new DeliveryProbe(fixture);
                    Long token = delivery.claim(event.eventId());
                    EventFixture.crash(args[2], "AFTER_CLAIM");
                    delivery.deliver(event, token, args[2]);
                } else {
                    fixture.produce(EventFixture.Boundary.valueOf(args[0]),
                        fixture.event(Long.parseLong(args[1]), 1), args[2]);
                }
            }
            return;
        }
        Path output = Path.of(System.getProperty("slotq.events.output",
            "build/reports/experiments/events/report.json")).toAbsolutePath();
        try (var mysql = new MySQLContainer(DockerImageName.parse("mysql:8.4"))) {
            mysql.start();
            try (var fixture = new EventFixture(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                fixture.schema();
                List<Map<String, Object>> rows = new ArrayList<>();
                for (var boundary : EventFixture.Boundary.values()) {
                    fixture.db.update("DELETE FROM fixture_projection");
                    fixture.db.update("DELETE FROM fixture_effect");
                    fixture.db.update("DELETE FROM fixture_event");
                    fixture.db.update("DELETE FROM fixture_owner");
                    int index = 0;
                    for (String fault : List.of("NONE", "BEFORE_COMMIT", "AFTER_COMMIT",
                        "HANDLER_FAILURE", "SLOW_HANDLER")) {
                        for (int repetition = 0; repetition < 3; repetition++) {
                            long seed = 80001L + index++;
                            var event = fixture.event(seed, 1);
                            long started = System.nanoTime();
                            int exitCode = 0;
                            if (fault.equals("AFTER_COMMIT")) {
                                exitCode = child(mysql, boundary.name(), seed, fault, 80);
                            } else {
                                try { fixture.produce(boundary, event, fault); }
                                catch (IllegalStateException expected) { exitCode = 1; }
                            }
                            double elapsed = (System.nanoTime() - started) / 1_000_000.0;
                            String handlerOutcome = "IN_PRODUCER_OR_NOT_REACHED";
                            if (boundary == EventFixture.Boundary.DURABLE
                                && !fault.equals("AFTER_COMMIT")
                                && fixture.count("fixture_event", "event_id", event.eventId()) == 1) {
                                try {
                                    fixture.tx.executeWithoutResult(status -> fixture.effect(event, fault));
                                    handlerOutcome = "SUCCESS";
                                } catch (IllegalStateException expected) { handlerOutcome = "TRANSIENT_FIXTURE"; }
                            }
                            Map<String, Object> before = fixture.snapshot(event);
                            if (boundary == EventFixture.Boundary.DURABLE
                                && fixture.count("fixture_event", "event_id", event.eventId()) == 1) {
                                child(mysql, "RECOVER", seed, "NONE", 0);
                            }
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("boundary", boundary.name());
                            row.put("fault", fault);
                            row.put("repetition", repetition);
                            row.put("seed", seed);
                            row.put("event", event);
                            row.put("exitCode", exitCode);
                            row.put("producerElapsedMs", elapsed);
                            row.put("handlerOutcome", handlerOutcome);
                            row.put("beforeRecovery", before);
                            row.put("afterRecovery", fixture.snapshot(event));
                            rows.add(row);
                        }
                    }
                }
                var probes = EventProbeWorkload.run(fixture, mysql);
                Map<String, Object> environment = new LinkedHashMap<>();
                environment.put("applicationRevision", git("rev-parse", "HEAD"));
                environment.put("branch", git("branch", "--show-current"));
                environment.put("dirty", !git("status", "--porcelain").isBlank());
                environment.put("javaVersion", System.getProperty("java.runtime.version"));
                environment.put("springBootVersion", SpringBootVersion.getVersion());
                environment.put("gradleVersion", System.getProperty("slotq.events.gradleVersion"));
                environment.put("mysqlVersion", fixture.db.queryForObject("SELECT VERSION()", String.class));
                environment.put("isolation", fixture.db.queryForObject("SELECT @@transaction_isolation", String.class));
                environment.put("poolSize", 10);
                environment.put("connectionTimeoutMs", 30000);
                environment.put("recordedAt", java.time.Instant.now().toString());
                environment.put("databaseImage", "mysql:8.4");
                environment.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
                environment.put("processors", Runtime.getRuntime().availableProcessors());
                environment.put("maxJvmMemoryBytes", Runtime.getRuntime().maxMemory());
                environment.put("jvmOptions", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
                environment.put("workload", Map.of("seed", 80001, "repetitions", 3,
                    "handlerDelayMs", 100, "duplicateWorkers", 4, "schema", "test-only fixture v1"));
                environment.put("runtimeConfiguration", Map.of("SYNCHRONOUS", "handler in business transaction",
                    "DIRECT", "process-local handler after commit; no recovery source",
                    "DURABLE", "same-transaction append; DB scan in fresh JVM; lease protocol probe: 3 attempts, 300ms DB lease, no backoff"));
                var mapper = new ObjectMapper();
                Files.createDirectories(output.getParent());
                Files.writeString(output, mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Map.of("schemaVersion", "slotq-events/v1",
                        "environment", environment, "rows", rows, "probes", probes,
                        "summary", EventEvidence.summarize(mapper.valueToTree(rows)))));
                System.out.println("Event evidence: " + output);
            }
        }
    }

    static int child(MySQLContainer mysql, String boundary,
                     long seed, String fault, int expectedExit) throws Exception {
        Path arguments = Files.createTempFile("slotq-event-jvm-", ".args");
        Path log = Files.createTempFile("slotq-event-jvm-", ".log");
        try {
            // Argument file avoids the Windows command-line limit. No credentials in args or logs.
            Files.writeString(arguments, "-cp\n\"" + System.getProperty("java.class.path")
                .replace("\\", "/") + "\"\n" + EventExperimentRunner.class.getName()
                + "\n" + boundary + "\n" + seed + "\n" + fault + "\n");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + arguments).redirectErrorStream(true).redirectOutput(log.toFile());
            process.environment().put("EVENT_DB_URL", mysql.getJdbcUrl());
            process.environment().put("EVENT_DB_USER", mysql.getUsername());
            process.environment().put("EVENT_DB_PASSWORD", mysql.getPassword());
            Process worker = process.start();
            if (!worker.waitFor(30, TimeUnit.SECONDS)) {
                worker.destroyForcibly().waitFor();
                throw new IllegalStateException("Child JVM did not reach crash point");
            }
            if (worker.exitValue() != expectedExit) {
                throw new IllegalStateException("Unexpected child exit " + worker.exitValue()
                    + ": " + Files.readString(log));
            }
            return worker.exitValue();
        } finally {
            Files.deleteIfExists(arguments);
            Files.deleteIfExists(log);
        }
    }

    private static String git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "safe.directory=C:/dev/slotq"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException("Cannot capture revision: " + result);
        return result.strip();
    }
}
