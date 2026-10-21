package com.slotq.evaluation;

import java.net.URI;
import java.time.Instant;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;

/** A manifest declaration alone cannot authorize Product mutation. No arbitrary JDBC target is supported. */
public final class M8MutationTarget {
    private final MySQLContainer container;
    private final URI origin;
    private M8MutationTarget(MySQLContainer container, URI origin) {
        this.container = container; this.origin = origin;
    }
    public static M8MutationTarget disposable(MySQLContainer container, String origin) {
        var target = new M8MutationTarget(container, URI.create(origin));
        target.check(); return target;
    }
    private void check() {
        require(container.isRunning() && container.getDockerImageName().matches("(?:docker.io/library/)?mysql:8\\.4(?:\\.[0-9]+)?"), "running disposable MySQL 8.4 required");
        require(container.getContainerId() != null && !container.isShouldBeReused(), "reusable MySQL is not disposable");
        require(origin.getScheme().equals("https") && SetHolder.LOOPBACK.contains(origin.getHost())
            && origin.getUserInfo() == null && origin.getPort() > 0, "controlled loopback TLS origin required");
    }
    void requireMatches(JsonNode manifest) {
        check(); M8EnvironmentContract.validate(manifest);
        equal(manifest, "kind", "EXECUTION");
        require(manifest.path("features").path("productWrite").asBoolean(), "Product writes disabled");
        equal(manifest.path("environment"), "dbHost", container.getJdbcUrl());
        equal(manifest.path("ingress"), "productOrigin", origin.toString());
    }

    public ObjectNode manifest(Instant baseTime, boolean productWrite, org.springframework.jdbc.core.JdbcTemplate database) throws java.io.IOException {
        check();
        var manifest = (ObjectNode) read("manifest-regression.json");
        manifest.put("kind", "EXECUTION"); ((ObjectNode) manifest.path("versions")).put("scorer", M8Evaluation.SCORER);
        var environment = (ObjectNode) manifest.path("environment");
        var storage = java.nio.file.Files.getFileStore(java.nio.file.Path.of("."));
        environment.put("measured", true).put("dbHost", container.getJdbcUrl()).put("dbUser", container.getUsername())
            .put("dbPasswordSource", "ENV_REF:TESTCONTAINERS_EPHEMERAL")
            .put("os", System.getProperty("os.name")).put("cpu", System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unavailable"))
            .put("cpuLimit", Runtime.getRuntime().availableProcessors()).put("memoryMiB", (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1048576))
            .put("jvmFlags", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString())
            .put("storage", storage.type() + "; " + container.getDockerImageName() + "; image=" + container.getContainerInfo().getImageId())
            .put("storageCapacityGiB", (int) Math.min(Integer.MAX_VALUE, storage.getTotalSpace() / 1073741824))
            .put("networkMeasurements", "observed loopback TLS and disposable container JDBC; no latency qualification");
        ((ObjectNode) manifest.path("features")).put("productWrite", productWrite);
        ((ObjectNode) manifest.path("ingress")).put("mcpOrigin", origin.toString()).put("productOrigin", origin.toString());
        ((ObjectNode) manifest.path("data")).put("baseTime", baseTime.toString());
        var data = (ObjectNode) manifest.path("data");
        for (var entry : java.util.Map.of("tenants", "tenants", "venues", "venues", "customers", "auth_principals",
                "slots", "slot_inventories", "reservations", "reservations", "documents", "knowledge_documents").entrySet()) {
            data.put(entry.getKey(), database.queryForObject("SELECT COUNT(*) FROM " + entry.getValue(), Integer.class));
        }
        return manifest;
    }
    private static final class SetHolder {
        static final java.util.Set<String> LOOPBACK = java.util.Set.of("localhost", "127.0.0.1", "[::1]");
    }
}
