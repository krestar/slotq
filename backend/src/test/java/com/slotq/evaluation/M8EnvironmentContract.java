package com.slotq.evaluation;

import java.net.URI;
import java.util.Set;
import java.util.List;
import java.util.HashSet;
import tools.jackson.databind.JsonNode;
import static com.slotq.evaluation.M8FixtureValidator.*;

/** Records selected limits; none of the template numbers is a qualified capacity or release threshold. */
final class M8EnvironmentContract {
    static void validateSet(List<JsonNode> manifests) {
        var ids = new HashSet<String>();
        var profiles = new HashSet<String>();
        for (var manifest : manifests) {
            validate(manifest);
            require(ids.add(text(manifest, "id")), "duplicate manifest ID");
            require(profiles.add(text(manifest, "profile")), "duplicate manifest profile");
        }
        require(profiles.equals(Set.of("SYNTHETIC_REGRESSION", "PRE_RELEASE")), "both environment profiles required");
    }

    static void validate(JsonNode manifest) {
        fields(manifest, "id version kind profile owner environment features ingress configuration data credentials provider evidence exclusions versions captureBeforeRun");
        require(text(manifest, "id").matches("[a-z0-9-]+"), "invalid manifest ID");
        version(manifest, "version");
        oneOf(manifest, "kind", Set.of("TEMPLATE", "EXECUTION"));
        oneOf(manifest, "profile", Set.of("SYNTHETIC_REGRESSION", "PRE_RELEASE"));
        require(Set.of(178, 179, 180, 181, 182, 183, 184, 185).contains(positive(manifest, "owner")), "unknown environment owner");
        var environment = manifest.path("environment");
        fields(environment, "location measured os cpu cpuLimit memoryMiB jvmFlags storage storageCapacityGiB network networkMeasurements java mysql instances transport ai ha publicExposure disposable dataClass devBootstrap dbPasswordSource dbUser dbHost");
        for (String key : Set.of("location", "os", "cpu", "jvmFlags", "storage", "network", "networkMeasurements", "dbHost")) text(environment, key);
        positive(environment, "cpuLimit");
        positive(environment, "memoryMiB");
        positive(environment, "storageCapacityGiB");
        require(environment.path("java").isIntegralNumber() && environment.path("java").asInt() == 25, "Java 25 required");
        equal(environment, "mysql", "8.4");
        require(positive(environment, "instances") == 1, "single live instance only");
        equal(environment, "transport", "DB_DIRECT");
        equal(environment, "ai", "PROCESS_LOCAL");
        equal(environment, "dataClass", "SYNTHETIC");
        require(!bool(environment, "ha") && !bool(environment, "publicExposure")
            && bool(environment, "disposable") && !bool(environment, "devBootstrap"), "unsupported or unsafe environment");
        reference(environment, "dbPasswordSource");
        require(!manifest.path("profile").asString().equals("PRE_RELEASE")
            || !Set.of("root", "admin").contains(text(environment, "dbUser")), "broad production database credential");
        boolean measured = bool(environment, "measured");
        if (manifest.path("kind").asString().equals("EXECUTION")) {
            require(measured, "execution needs actual host measurements");
            for (String key : Set.of("os", "cpu", "jvmFlags", "storage", "networkMeasurements")) {
                require(!text(environment, key).equals("TO_BE_CAPTURED"), "execution needs actual host measurements: " + key);
            }
        }

        var features = manifest.path("features");
        fields(features, "mcp productWrite knowledge router runtime eventWorker waitlistMaintenance recovery kafka");
        for (String key : features.propertyNames()) bool(features, key);
        require(!features.path("kafka").asBoolean(), "Kafka qualification is a separate excluded profile");
        var ingress = manifest.path("ingress");
        fields(ingress, "tlsEnabled mcpOrigin productOrigin corsOrigins publicSurfaces");
        require(bool(ingress, "tlsEnabled") && strings(ingress, "publicSurfaces").isEmpty(), "TLS and controlled ingress required");
        String origin = text(ingress, "mcpOrigin");
        require(origin.equals(text(ingress, "productOrigin")), "co-located fixed Product origin required");
        for (String value : java.util.stream.Stream.concat(java.util.stream.Stream.of(origin), strings(ingress, "corsOrigins").stream()).toList()) {
            URI uri = URI.create(value);
            require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                && (uri.getPath() == null || uri.getPath().isEmpty()) && uri.getQuery() == null
                && uri.getFragment() == null, "explicit HTTPS origin required");
        }
        var config = manifest.path("configuration");
        fields(config, "hikariMaximumPoolSize connectionTimeoutMs connectTimeoutMs socketTimeoutMs lockWaitSeconds queryTimeoutMs transactionTimeoutSeconds httpThreads mcpWorkers mcpRate mcpBurst mcpConcurrency mcpHandlerTimeoutSeconds providerWorkers providerConcurrency runtimeSteps runtimeProviderAttempts runtimeToolAttempts runtimeTokens runtimeOutputTokens runtimeLifetimeSeconds runtimeAttemptTimeoutSeconds maximumDollars eventBatchSize eventLeaseSeconds eventMaxAttempts eventEffectTimeoutSeconds eventPollSeconds waitlistBatchSize waitlistPollSeconds");
        for (String key : config.propertyNames()) {
            if (!key.equals("maximumDollars")) positive(config, key);
        }
        require(config.path("maximumDollars").isIntegralNumber() && config.path("maximumDollars").asInt() == 0, "paid budget forbidden");
        require(config.path("httpThreads").asInt() > config.path("mcpWorkers").asInt(), "Product HTTP thread headroom missing");
        require(config.path("mcpWorkers").asInt() >= config.path("mcpConcurrency").asInt(), "invalid MCP admission capacity");
        require(config.path("providerWorkers").asInt() >= config.path("providerConcurrency").asInt(), "invalid provider admission capacity");
        require(config.path("runtimeSteps").asInt() <= 64 && config.path("runtimeProviderAttempts").asInt() <= 32
            && config.path("runtimeToolAttempts").asInt() <= 32 && config.path("runtimeTokens").asInt() <= 10_000_000
            && config.path("runtimeOutputTokens").asInt() <= 65536 && config.path("runtimeLifetimeSeconds").asInt() <= 900
            && config.path("runtimeAttemptTimeoutSeconds").asInt() <= 45, "Runtime contract bounds exceeded");
        var data = manifest.path("data");
        fields(data, "tenants venues customers slots reservations documents seed baseTime venueTimezone");
        for (String key : Set.of("tenants", "venues", "customers", "slots", "reservations", "documents", "seed")) positive(data, key);
        require(data.path("tenants").asInt() >= 2 && data.path("venues").asInt() >= 3
            && data.path("customers").asInt() >= 3, "isolation fixture scale missing");
        java.time.Instant.parse(text(data, "baseTime"));
        java.time.ZoneId.of(text(data, "venueTimezone"));

        var credentials = manifest.path("credentials");
        fields(credentials, "product originalActor approval source valueRecorded");
        equal(credentials, "product", "NARROWED_PRODUCT");
        equal(credentials, "originalActor", "CONTROLLED_INDIVIDUAL");
        equal(credentials, "approval", "EXACT_ORIGINAL_ACTOR");
        reference(credentials, "source");
        require(!bool(credentials, "valueRecorded"), "credential value must never be recorded");
        validateProvider(manifest.path("provider"), manifest.path("kind").asString().equals("EXECUTION"));

        var evidence = manifest.path("evidence");
        fields(evidence, "root access retentionDays gitTracked rawSecrets");
        String root = text(evidence, "root");
        require(root.matches("build/m8-evidence(?:/[a-zA-Z0-9_-]+)*"), "evidence must remain under ignored build/m8-evidence");
        equal(evidence, "access", "EXECUTOR_AND_AUTHORIZED_REVIEWERS");
        positive(evidence, "retentionDays");
        require(!bool(evidence, "gitTracked") && !bool(evidence, "rawSecrets"), "protected evidence policy");
        require(strings(manifest, "exclusions").equals(Set.of("PUBLIC_LOGIN", "RESTART_RUN_CONTINUITY", "MULTI_INSTANCE",
            "HA_SLA", "KAFKA_QUALIFICATION", "REAL_CUSTOMER_AI_DATA")), "unsupported scope exclusions required");
        var versions = manifest.path("versions");
        fields(versions, "dataset oracle criteria prompt schema plan context scorer support");
        for (String key : Set.of("dataset", "oracle", "criteria")) version(versions, key);
        for (String key : Set.of("prompt", "schema", "plan", "context", "scorer", "support")) text(versions, key);
        require(strings(manifest, "captureBeforeRun").equals(Set.of("sourceRevision", "sourceDirty", "buildDigest", "configDigest",
            "schemaDigest", "datasetDigest", "oracleDigest", "promptDigest", "scorerDigest", "criteriaDigest",
            "hostAndContainerMeasurements", "actualBindings", "credentialProvisioningReference", "providerTermsAndTierEvidence")), "execution identity capture incomplete");
        rejectSensitive(manifest);
    }

    private static void validateProvider(JsonNode provider, boolean execution) {
        fields(provider, "enabled endpoint models api credentialSource freeTierAttested paidCalls syntheticOnly trainingHumanReviewAllowed region retention maximumCalls maximumDollars automaticRetry fallback termsCheckedAt");
        boolean enabled = bool(provider, "enabled");
        equal(provider, "endpoint", "https://generativelanguage.googleapis.com/v1beta/models/");
        require(URI.create(text(provider, "endpoint")).getUserInfo() == null, "credential in URL");
        require(strings(provider, "models").equals(Set.of("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")), "fixed approved models required");
        equal(provider, "api", "generateContent-standard-free");
        require(!bool(provider, "paidCalls") && bool(provider, "syntheticOnly")
            && bool(provider, "trainingHumanReviewAllowed") && !bool(provider, "automaticRetry") && !bool(provider, "fallback"), "unsupported provider policy");
        equal(provider, "region", "UNAVAILABLE");
        equal(provider, "retention", "UNAVAILABLE");
        positive(provider, "maximumCalls");
        require(provider.path("maximumDollars").isIntegralNumber() && provider.path("maximumDollars").asInt() == 0, "provider paid budget");
        bool(provider, "freeTierAttested");
        text(provider, "termsCheckedAt");
        if (enabled) {
            reference(provider, "credentialSource");
            if (execution) {
                require(provider.path("freeTierAttested").asBoolean(), "free tier must be attested before actual call");
                java.time.Instant.parse(text(provider, "termsCheckedAt"));
            }
        } else equal(provider, "credentialSource", "NONE");
    }

    private static void reference(JsonNode node, String key) {
        require(text(node, key).matches("ENV_REF:[A-Z][A-Z0-9_]+"), "secret reference required, never its value");
    }
}
