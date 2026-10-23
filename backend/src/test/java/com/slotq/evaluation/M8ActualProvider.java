package com.slotq.evaluation;

import com.slotq.ai.router.*;
import com.slotq.ai.runtime.RuntimeContract.Provider;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;

/** Reuses the M7 single-attempt adapter. Only explicit opt-in can construct a live provider. */
public final class M8ActualProvider implements Provider {
    private final JsonNode plan;
    private final GeminiAdapter adapter;
    private final List<Map<String, Object>> captured = new ArrayList<>();
    private final Instant deadline;
    private final String key;
    private final HttpClient http;
    private boolean metadataChecked;
    private int calls, cellCalls;
    private long tokens;
    private long cellTokens;
    private JsonNode cellReservation;
    private Instant lastCall;
    private boolean stopped;
    private M8Evaluation.Recorder recorder;
    private M8Evaluation.Cell cell;

    public M8ActualProvider(JsonNode plan) throws Exception {
        require(Boolean.getBoolean("slotq.m8.modelOptIn") && Boolean.getBoolean("slotq.m8.modelFreeTier")
            && "false".equals(System.getProperty("slotq.m8.modelPaidCalls")), "explicit free-only provider opt-in required");
        equal(plan, "kind", "EXECUTION");
        this.plan = plan;
        deadline = Instant.now().plusSeconds(plan.path("maximumSeconds").asLong());
        String supplied = System.getenv("GEMINI_API_KEY");
        if (supplied == null || supplied.isBlank()) {
            var values = Files.readAllLines(Path.of("../.env.local")).stream().map(s -> s.replace("\uFEFF", ""))
                .filter(s -> s.startsWith("GEMINI_API_KEY=")).map(s -> s.substring(15).strip()).toList();
            require(values.size() == 1 && !values.getFirst().isBlank(), "local provider credential unavailable"); supplied = values.getFirst();
        }
        key = supplied;
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5)).build();
        adapter = com.slotq.ai.router.RepresentativeFlowEvidence.adapter(http, key, captured::add);
    }

    public synchronized void enter(M8Evaluation.Cell cell, M8Evaluation.Recorder recorder) {
        this.cell = cell; this.recorder = recorder; cellCalls = 0; cellTokens = 0;
        int index = M8ModelQualification.cells(plan).indexOf(cell);
        require(index >= 0, "provider cell outside frozen schedule"); cellReservation = plan.path("cellReservations").get(index);
    }

    @Override public synchronized GeminiAdapter.Result generate(String model, ModelRouter.Classification classification,
            ProviderProtocol.Request request, ProviderBudget budget) {
        require(recorder != null && model.equals(cell.model()), "Router selected a different model; never override Router");
        long reservation = plan.path("reservationTokens").asLong();
        require(!stopped && calls < plan.path("maximumCalls").asInt() && cellCalls < cellReservation.path("providerCalls").asInt()
            && cellTokens + reservation <= cellReservation.path("tokens").asLong() && tokens + reservation <= plan.path("maximumTokens").asLong()
            && Instant.now().isBefore(deadline), "provider budget/deadline/stop reached");
        require(!request.prompt().contains(key), "unsafe synthetic provider input");
        rejectSensitive(JSON.valueToTree(request));
        if (!metadataChecked) {
            try {
                var metadata = ModelComparisonRunner.candidateMetadata(http, key);
                recorder.put("provider-metadata", metadata); metadataChecked = true;
            } catch (Exception failedPreflight) {
                stopped = true;
                try { recorder.put("provider-preflight-failure", Map.of("kind", "MODEL_METADATA_UNAVAILABLE", "generationCalls", 0)); }
                catch (Exception failedEvidence) { throw invalid("provider preflight evidence unavailable"); }
                throw invalid("model/API metadata unavailable; no generation call");
            }
        }
        if (lastCall != null) {
            long millis = Duration.between(Instant.now(), lastCall.plusMillis(plan.path("minimumSpacingMillis").asLong())).toMillis();
            if (millis > 0) {
                require(Instant.now().plusMillis(millis).isBefore(deadline), "provider spacing exceeds deadline");
                try { Thread.sleep(millis); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw invalid("provider spacing interrupted");
                }
            }
        }
        captured.clear(); lastCall = Instant.now();
        // The same absolute experiment deadline also caps a Runtime-supplied provider budget.
        long remainingMillis = Duration.between(Instant.now(), deadline).toMillis();
        require(remainingMillis > 0, "provider deadline reached");
        // Runtime still owns its original cumulative budget; reserve and settle both boundaries.
        long runReservation = reservation;
        long runTimeout = budget.begin(runReservation);
        var limited = new ProviderBudget(Clock.systemUTC(), Instant.now().plusMillis(Math.min(runTimeout, remainingMillis)), 1, reservation);
        GeminiAdapter.Result result;
        try { result = adapter.generate(model, classification, request, limited); }
        catch (RuntimeException failure) {
            stopped = true; budget.end(runReservation, null); throw failure;
        }
        Long observed = result.usage().total();
        budget.end(runReservation, result.attempts() == 0 ? 0L : observed);
        calls += result.attempts();
        tokens += observed == null ? result.attempts() * reservation : observed;
        cellTokens += observed == null ? result.attempts() * reservation : observed;
        boolean versionMismatch = plan.path("phase").asString().equals("QUALIFICATION") && result.attempts() > 0
            && !Objects.equals(result.resolvedVersion(), plan.path("providerPreflight").path("modelVersions").path(model).asString());
        stopped = result.failure() != null || result.attempts() > 0 && (observed == null || observed > reservation || result.resolvedVersion() == null)
            || versionMismatch || tokens > plan.path("maximumTokens").asLong();
        var journal = JSON.createObjectNode(); journal.put("model", model).put("startedAt", lastCall.toString())
            .put("reservationTokens", reservation).put("api", GeminiAdapter.API_MODE).put("endpoint", GeminiAdapter.ENDPOINT).put("versionMismatch", versionMismatch);
        journal.set("request", JSON.valueToTree(request)); journal.set("result", JSON.valueToTree(result));
        journal.set("wire", JSON.valueToTree(captured));
        String artifact = "provider-call-" + (char) ('a' + cellCalls++);
        try { recorder.put(artifact, journal); }
        catch (Exception failedEvidence) {
            stopped = true;
            // Preserve observable accounting even when the content policy withholds response bytes.
            var sanitized = JSON.createObjectNode().put("model", model).put("reservationTokens", reservation).put("contentWithheld", true);
            var metadata = (ObjectNode) JSON.valueToTree(result); metadata.remove("output"); sanitized.set("result", metadata);
            try { recorder.put(artifact, sanitized); } catch (Exception unavailable) { throw invalid("provider accounting unavailable"); }
            throw invalid("provider content withheld; stop before another call");
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        var plan = M8ModelQualification.load(); var cells = M8ModelQualification.cells(plan);
        require(cells.stream().allMatch(c -> c.lane().equals("ACTUAL_PROVIDER_FIXTURE")), "fixture task accepts fixture lane only");
        String runId = "model-fixture-" + UUID.randomUUID();
        var manifest = (ObjectNode) plan.path("manifest").deepCopy(); M8ModelQualification.enableProvider(manifest, plan);
        var groups = new HashMap<String, JsonNode>();
        var session = new M8Evaluation(runId, cells, manifest, M8ModelQualification.budget(plan), c -> groups.computeIfAbsent(
            M8Bindings.group(M8Evaluation.item(c.caseId())) + c.repeat() + c.model() + c.lane(),
            ignored -> M8Bindings.create(c, runId, Instant.parse(manifest.path("data").path("baseTime").asString()), Map.of())), null, plan);
        var provider = new M8ActualProvider(plan);
        var specification = JSON.readTree(resource("model-router/comparison-v3.json"));
        session.execute((cell, input, recorder) -> {
            provider.enter(cell, recorder);
            var testCase = JSON.createObjectNode(); testCase.set("input", input.path("input")); testCase.set("fixture", input.path("facts"));
            testCase.set("workload", M8Evaluation.item(cell.caseId()).path("workload"));
            var request = ModelComparisonRunner.request(specification, testCase);
            var projected = (ObjectNode) input.deepCopy();
            projected.setAll((ObjectNode) JSON.valueToTree(request)); recorder.put("request", projected);
            var classification = new ModelRouter.Classification(ModelRouter.DataClass.SYNTHETIC, true, true, null, null, 40000, 1024);
            var response = provider.generate(cell.model(), classification, request,
                new ProviderBudget(Clock.systemUTC(), Instant.now().plusSeconds(45), 1, plan.path("reservationTokens").asLong()));
            var raw = JSON.createObjectNode(); raw.put("observationKind", "ACTUAL_PROVIDER_SYNTHETIC_FIXTURE")
                .put("providerCalls", response.attempts()).put("tokens", response.usage().total() == null ? 0 : response.usage().total())
                .put("usageUnknown", response.attempts() > 0 && response.usage().total() == null)
                .put("failure", response.failure() == null ? "" : response.failure().name());
            raw.set("proposal", JSON.valueToTree(response.output())); recorder.put("result", raw);
            if (response.output() != null) recorder.put("answer", response.output());
            // Fixture proposals cannot establish server dispatch, authority or independent DB effects.
        });
        Files.writeString(Path.of("../build/m8-evidence/latest-model-fixture-run.txt"), runId + "\n" + session.evidenceDigest());
    }
}
