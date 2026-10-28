package com.slotq.mcp;

import com.slotq.SlotqApplication;
import com.slotq.ai.flow.*;
import com.slotq.ai.router.*;
import com.slotq.ai.runtime.*;
import com.slotq.auth.access.*;
import com.slotq.integration.mcp.product.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.*;
import com.slotq.venue.domain.VenueId;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import javax.net.ssl.SSLContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static com.slotq.ai.runtime.RuntimeContract.*;

/** Test-only co-located Agent/MCP/Product JVM. The parent's MySQL survives forced termination. */
public final class AgentLifetimeProcessProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        // Credentials travel through stdin, never command arguments, checkpoint files or console output.
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        JsonNode config = JSON.readTree(input.readLine());
        Path directory = Path.of(config.path("directory").asString());
        var properties = new HashMap<String,Object>();
        properties.put("spring.datasource.url", config.path("jdbcUrl").asString());
        properties.put("spring.datasource.username", config.path("jdbcUser").asString());
        properties.put("spring.datasource.password", config.path("jdbcPassword").asString());
        properties.put("spring.datasource.hikari.connection-timeout", "2000");
        properties.put("spring.datasource.hikari.maximum-pool-size", "4");
        properties.put("server.tomcat.threads.max", "64");
        properties.put("spring.datasource.hikari.data-source-properties.socketTimeout", "2000");
        properties.put("spring.datasource.hikari.data-source-properties.connectTimeout", "2000");
        properties.put("spring.datasource.hikari.connection-init-sql",
            "SET SESSION innodb_lock_wait_timeout=2, lock_wait_timeout=2, wait_timeout=5, max_execution_time=2000");
        properties.put("spring.transaction.default-timeout", "10s");
        properties.put("slotq.mcp.enabled", "true");
        properties.put("slotq.mcp.origin", config.path("origin").asString());
        properties.put("slotq.mcp.product-origin", config.path("origin").asString());
        properties.put("server.port", config.path("port").asInt());
        properties.put("server.ssl.enabled", "true");
        properties.put("server.ssl.key-store", config.path("keyStore").asString());
        properties.put("server.ssl.key-store-password", config.path("keyStorePassword").asString());
        properties.put("server.ssl.key-store-type", "PKCS12");
        properties.put("slotq.probe.directory", directory.toString());
        properties.put("slotq.probe.point", config.path("killPoint").asString());
        properties.put("slotq.probe.venue", config.path("venue").asString());
        properties.put("slotq.mcp.handler-budget", "PT5S");
        properties.put("slotq.mcp.workers", "8");
        properties.put("slotq.mcp.ingress", "8");
        properties.put("slotq.mcp.quota.rate", "200");
        properties.put("slotq.mcp.quota.burst", "200");
        properties.put("slotq.mcp.quota.concurrency", "4");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.root", "WARN");
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("lifetime-probe", properties));
        try (var application = new SpringApplicationBuilder(SlotqApplication.class, ProbeConfiguration.class)
                .web(WebApplicationType.SERVLET).environment(environment).run()) {
            var authority = application.getBean(ActorAccess.class);
            var clock = Clock.systemUTC();
            var approvals = application.getBean(HoldApprovals.class);
            var registry = application.getBean(ToolRegistry.class);
            var engine = application.getBean(McpEngine.class);
            try (var runtime = new AgentRuntime(authority, registry, engine, approvals,
                        (model, classification, request, budget) -> {
                            budget.begin(2000);
                            budget.end(2000, 100L);
                            return RepresentativeFlowIntegrationTests.response(new ProviderProtocol.Output(
                                "PROPOSE_HOLD", "reservation.hold", config.path("slot").asString(), 2,
                                "NOT_APPLICABLE", List.of(), List.of(), "원 고객의 승인이 필요합니다."), null, true);
                        }, RepresentativeRouterProfile.candidates(), new RepresentativeContext(actor -> true),
                        quota(), clock, 8, 2)) {
                String token = config.path("delegation").asString();
                var flows = new RepresentativeFlows(runtime, Limits.bounded());
                if (config.path("restart").asBoolean()) {
                    UUID previous = UUID.fromString(config.path("oldRun").asString());
                    try {
                        runtime.result(previous, token);
                        throw new AssertionError("Old Run unexpectedly resumed");
                    } catch (RuntimeFailure expected) {
                        if (!expected.getMessage().equals("AUTHORITY")) {
                            throw expected;
                        }
                    }
                    checkpoint(directory, "restarted", Map.of("oldRunRejected", true, "workers", runtime.activeWorkers()));
                    String recovery = input.readLine();
                    if (!List.of("recover", "recover-revoked").contains(recovery)) {
                        throw new AssertionError("Explicit recovery required");
                    }
                    if (recovery.equals("recover-revoked")) {
                        try {
                            flows.customerRead(token, UUID.fromString(config.path("knownTarget").asString()), null);
                            throw new AssertionError("Revoked authority admitted a new Run");
                        } catch (RuntimeFailure denied) {
                            if (!denied.getMessage().equals("AUTHORITY")) throw denied;
                        }
                        checkpoint(directory, "recovered", Map.of("outcome", "denied-current-authority", "dispatched", false));
                    } else if (config.path("knownTarget").isTextual()) {
                        var read = flows.customerRead(token, UUID.fromString(config.path("knownTarget").asString()), null);
                        var result = flows.prepareReads(read, token);
                        checkpoint(directory, "recovered", Map.of("outcome", result.tools().getLast().structured().get("outcome"),
                            "tool", result.tools().getLast().tool(), "mutationEffect", result.productEffect().name(),
                            "providerAttempts", result.budget().providerAttempts(), "toolAttempts", result.budget().toolAttempts()));
                    } else {
                        checkpoint(directory, "recovered", Map.of("outcome", "unknown", "dispatched", false));
                    }
                } else {
                    var flow = flows.customerHold(token, UUID.fromString(config.path("slot").asString()), 2, null);
                    runtime.generate(flow.id(), token);
                    var review = runtime.reviewHold(flow.id(), token, config.path("original").asString());
                    runtime.approveHold(flow.id(), token, config.path("original").asString(), review);
                    checkpoint(directory, "prepared", Map.of("runId", flow.id().toString(), "intentId", review.intentId().toString(),
                        "execution", runtime.result(flow.id(), token).execution().name(),
                        "providerAttempts", runtime.result(flow.id(), token).budget().providerAttempts()));
                    if (!"dispatch".equals(input.readLine())) {
                        throw new AssertionError("Explicit dispatch required");
                    }
                    var result = runtime.dispatchHold(flow.id(), token);
                    var observed = new LinkedHashMap<String,Object>();
                    observed.put("effect", result.productEffect().name());
                    observed.put("knownTarget", result.knownTarget() == null ? null : result.knownTarget().toString());
                    observed.put("invocation", result.tools().getLast().invocation().name());
                    checkpoint(directory, "returned", observed);
                    if (!"finish".equals(input.readLine())) {
                        throw new AssertionError("Awaiting forced termination");
                    }
                }
            }
        }
    }

    @TestConfiguration
    static class ProbeConfiguration {
        @Bean SSLContext probeTrust() throws Exception {
            return McpHttpIntegrationTests.tls();
        }

        @Bean FilterRegistrationBean<Filter> probeCheckpoints(@Value("${slotq.probe.directory}") String directory,
                @Value("${slotq.probe.point}") String point, @Value("${slotq.probe.venue}") UUID venue) {
            var fault = new ProductToolsIntegrationTests.ResponseFault();
            boolean responseLoss = point.equals("AFTER_COMMIT") || point.equals("AFTER_RESPONSE_LOSS");
            if (responseLoss) {
                fault.arm(new VenueId(venue), true, point.equals("AFTER_COMMIT"));
            }
            Filter filter = (request, response, chain) -> {
                var http = (HttpServletRequest) request;
                if (!http.getRequestURI().equals("/api/v1/venues/" + venue + "/reservations/holds")) {
                    chain.doFilter(request, response);
                    return;
                }
                checkpoint(Path.of(directory), "accepted", Map.of("productHttpEntered", true));
                FilterChain committed = (r, s) -> {
                    chain.doFilter(r, s);
                    if (((HttpServletResponse) s).getStatus() == 201) {
                        checkpoint(Path.of(directory), "committed", Map.of("productCommitObserved", true));
                    }
                };
                if (responseLoss) {
                    fault.doFilter(request, response, committed);
                } else {
                    committed.doFilter(request, response);
                }
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(-99);
            return registration;
        }
    }

    private static LocalAdmission quota() {
        return new LocalAdmission(new LocalAdmission.Limit(100, 100, 2), 64, System::nanoTime);
    }

    private static void checkpoint(Path directory, String name, Map<String,Object> value) throws IOException {
        Path temporary = directory.resolve(name + ".tmp");
        var observation = new LinkedHashMap<>(value);
        observation.put("at", Instant.now().toString());
        observation.put("nanos", System.nanoTime());
        observation.put("pid", ProcessHandle.current().pid());
        Files.writeString(temporary, JSON.writeValueAsString(observation));
        Files.move(temporary, directory.resolve(name + ".json"), StandardCopyOption.ATOMIC_MOVE);
    }
}
