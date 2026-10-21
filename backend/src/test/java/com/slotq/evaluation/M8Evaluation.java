package com.slotq.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static com.slotq.evaluation.M8FixtureValidator.*;

/** Test-only planned execution and offline grading. No provider credentials, HTTP client or DB writer. */
public final class M8Evaluation {
    public static final String SCORER = "m8-scorer-v1";
    public record Cell(String caseId, String caseVersion, int repeat, String model, String lane) { }
    public record Budget(int maximumCells, int maximumProviderCalls, long maximumTokens, Instant deadline, boolean stopOnHardGate) { }
    @FunctionalInterface public interface Executor { void execute(Cell cell, JsonNode modelInput, Recorder recorder) throws Exception; }
    private static final List<String> CODE = List.of("M8Evaluation", "M8Scorer", "M8Bindings", "M8ProtectedFiles", "M8MutationTarget",
        "M8RunContract", "M8EnvironmentContract", "M8FixtureValidator");
    private final Path root;
    private final ObjectNode plan;
    private final List<Cell> cells;
    private final M8MutationTarget target;
    private final Map<String, JsonNode> resolvedGroups = new HashMap<>();
    private boolean started;

    public static JsonNode item(String id) {
        return array(read("dataset.json"), "cases").stream().filter(c -> c.path("id").asString().equals(id)).findFirst()
            .orElseThrow(() -> invalid("unknown case ID"));
    }

    public static List<Cell> cells(List<String> caseIds, Set<String> models, Set<String> lanes, int repeats) {
        require(!caseIds.isEmpty() && new HashSet<>(caseIds).size() == caseIds.size() && !models.isEmpty()
            && !lanes.isEmpty() && repeats > 0 && repeats <= 1000, "invalid execution selection");
        var cells = new ArrayList<Cell>();
        for (String id : caseIds) {
            var item = item(id);
            for (String model : new TreeSet<>(models)) for (String lane : new TreeSet<>(lanes)) {
                require(strings(item, "lanes").contains(lane), "unsupported case lane");
                require(model.matches("[a-zA-Z0-9._-]{1,100}"), "invalid model identity");
                for (int repeat = 1; repeat <= repeats; repeat++) cells.add(new Cell(id, text(item, "version"), repeat, model, lane));
            }
        }
        return List.copyOf(cells);
    }

    public M8Evaluation(String runId, List<Cell> cells, JsonNode manifest, Budget budget,
                        Function<Cell, JsonNode> bindings, M8MutationTarget target) throws Exception {
        M8FixtureValidator.main(new String[0]);
        require(!cells.isEmpty() && new HashSet<>(cells).size() == cells.size(), "empty or duplicate planned cell");
        require(budget.maximumCells() > 0 && budget.maximumProviderCalls() >= 0 && budget.maximumTokens() >= 0
            && budget.deadline().isAfter(Instant.now()), "finite pre-run budget required");
        M8EnvironmentContract.validate(manifest);
        require(manifest.path("versions").path("scorer").asString().equals(SCORER), "execution scorer version");
        require(!manifest.path("provider").path("enabled").asBoolean(), "#180 owns actual-provider execution; import observed evidence through its adapter");
        for (Cell cell : cells) {
            require(cell.caseVersion().equals(text(item(cell.caseId()), "version")) && cell.repeat() > 0
                && strings(item(cell.caseId()), "lanes").contains(cell.lane()), "invalid planned cell identity");
            require(cell.lane().equals("DETERMINISTIC") || cell.lane().equals("FAULT_INJECTION"), "new provider evidence requires #180 adapter");
        }
        if (manifest.path("features").path("productWrite").asBoolean()) {
            require(target != null, "mutation environment requires verified disposable MySQL target"); target.requireMatches(manifest);
        }
        this.target = target; this.cells = List.copyOf(cells); this.root = M8ProtectedFiles.root(runId);
        String access = M8ProtectedFiles.create(root);
        plan = JSON.createObjectNode();
        plan.put("version", "1.0.0").put("runId", runId).put("phase", "REGRESSION").put("scorer", SCORER)
            .put("frozenAt", Instant.now().toString()).put("dataClass", "SYNTHETIC");
        plan.set("cells", JSON.valueToTree(cells)); plan.set("budget", JSON.valueToTree(budget)); plan.set("manifest", manifest);
        plan.putObject("perCellReservation").put("providerCalls", budget.maximumProviderCalls() / cells.size())
            .put("tokens", budget.maximumTokens() / cells.size());
        plan.set("access", JSON.valueToTree(Map.of("protectionReference", root.toString(), "checkedAt", Instant.now().toString(),
            "checkedBy", System.getProperty("user.name"), "acl", access, "retentionDays", manifest.path("evidence").path("retentionDays").asInt())));
        var snapshots = plan.putObject("snapshots");
        for (String file : List.of("dataset.json", "case-history.json", "criteria.json", "review-rubric.json")) {
            snapshots.set(file, writeArtifact("snapshots/" + file, resource(ROOT + file)));
        }
        for (var source : read("dataset.json").path("sources")) {
            String path = source.path("path").asString(); snapshots.set(path, writeArtifact("snapshots/" + path, resource(path)));
        }
        var sourceBuild = JSON.createObjectNode();
        sourceBuild.put("sourceRevision", git("rev-parse", "HEAD")).put("sourceDirty", !git("status", "--porcelain").isEmpty());
        var code = sourceBuild.putObject("code");
        for (String name : CODE) {
            var path = Path.of("src/test/java/com/slotq/evaluation/" + name + ".java");
            code.set(name, writeArtifact("snapshots/code/" + name + ".java", Files.readAllBytes(path)));
        }
        sourceBuild.put("scorerDigest", digest(JSON.writeValueAsBytes(code)));
        var classes = sourceBuild.putObject("classes");
        for (Class<?> type : List.of(M8Evaluation.class, M8Scorer.class, M8Bindings.class, M8ProtectedFiles.class, M8MutationTarget.class,
                com.slotq.ai.router.ProviderProtocol.class, com.slotq.ai.router.ModelComparisonRunner.class,
                com.slotq.ai.runtime.AgentRuntime.class, com.slotq.ai.flow.RepresentativeContext.class)) {
            String name = type.getName().replace('.', '/');
            classes.set(name, writeArtifact("snapshots/classes/" + name + ".class", classBytes(type)));
        }
        var compiledBuild = writeArtifact("snapshots/compiled-build.zip", compiledBuild());
        sourceBuild.set("compiledBuild", compiledBuild); sourceBuild.set("buildDigest", compiledBuild.path("digest"));
        sourceBuild.put("promptDigest", digest(classBytes(com.slotq.ai.flow.RepresentativeContext.class)));
        sourceBuild.put("schemaDigest", digest(com.slotq.ai.router.ProviderProtocol.SCHEMA_JSON.getBytes(StandardCharsets.UTF_8)));
        sourceBuild.put("configDigest", digest(JSON.writeValueAsBytes(manifest)));
        snapshots.set("source-build", writeArtifact("snapshots/source-build.json", JSON.writeValueAsBytes(sourceBuild)));
        snapshots.set("manifest", writeArtifact("snapshots/manifest.json", JSON.writeValueAsBytes(manifest)));
        var bindingRefs = plan.putArray("bindings");
        var groups = new HashMap<String, JsonNode>();
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = cells.get(i); JsonNode binding = bindings.apply(cell);
            M8Bindings.validate(binding, new M8Bindings.CellIdentity(runId, cell), item(cell.caseId()));
            String groupKey = groupKey(cell);
            var previous = groups.putIfAbsent(groupKey, binding);
            if (previous != null) require(previous.equals(binding), "shared fixture group rebound");
            bindingRefs.add(writeArtifact("bindings/" + i + ".json", JSON.writeValueAsBytes(binding)));
        }
        rejectSensitive(plan);
        writeNew(root.resolve("plan.json"), JSON.writeValueAsBytes(plan));
        writeNew(root.resolve("plan.sha256"), digest(Files.readAllBytes(root.resolve("plan.json"))).getBytes(StandardCharsets.UTF_8));
    }

    public final class Recorder {
        private final int index;
        private final ObjectNode record;
        private final ObjectNode currentBinding;
        private final Map<String, JsonNode> artifacts = new TreeMap<>();
        private Recorder(int index) throws Exception {
            this.index = index; this.record = JSON.createObjectNode();
            JsonNode initial = readArtifact(root, plan.path("bindings").get(index));
            this.currentBinding = (ObjectNode) resolvedGroups.getOrDefault(groupKey(cells.get(index)), initial).deepCopy();
            record.set("cell", JSON.valueToTree(cells.get(index)));
            record.put("startedAt", Instant.now().toString()).put("endedAt", "").put("stopReason", "RUNNING");
            record.set("artifacts", JSON.createObjectNode()); persist();
        }
        public void put(String id, Object observation) throws Exception {
            require(id.matches("[a-z-]+") && !artifacts.containsKey(id), "duplicate/unsafe artifact ID");
            JsonNode value = observation instanceof JsonNode n ? n : JSON.valueToTree(observation);
            try { rejectSensitive(value); }
            catch (IllegalArgumentException unsafe) {
                record.withArray("rejectedArtifacts").addObject().put("id", id).put("digest", digest(JSON.writeValueAsBytes(value)));
                persist();
                throw invalid("sensitive artifact withheld");
            }
            var ref = writeArtifact("cells/" + index + "/" + id + ".json", JSON.writeValueAsBytes(value));
            artifacts.put(id, value); ((ObjectNode) record.path("artifacts")).set(id, ref); persist();
        }
        public JsonNode binding() { return currentBinding.deepCopy(); }
        public void bindCreatedReservation(String symbol, UUID actual) {
            require(symbol.startsWith("reservation-"), "only a generated Reservation may complete a binding");
            String previous = currentBinding.path("symbols").path(symbol).asString();
            require(previous.equals("UNBOUND") || previous.equals(actual.toString()), "fixture symbol already assigned");
            ((ObjectNode) currentBinding.path("symbols")).put(symbol, actual.toString());
            resolvedGroups.put(groupKey(cells.get(index)), currentBinding.deepCopy());
        }
        private void persist() throws IOException {
            Path path = root.resolve("cells/" + index + "/record.json"); Files.createDirectories(path.getParent());
            Files.write(path, JSON.writeValueAsBytes(record));
        }
    }

    public synchronized JsonNode execute(Executor executor) throws Exception {
        require(!started, "run already started; choose a new runId");
        started = true;
        long calls = 0, tokens = 0; int executed = 0;
        var budget = plan.path("budget"); String stop = "COMPLETED";
        var reservation = plan.path("perCellReservation");
        for (int i = 0; i < cells.size(); i++) {
            if (executed >= budget.path("maximumCells").asInt() || calls >= budget.path("maximumProviderCalls").asInt()
                    && budget.path("maximumProviderCalls").asInt() > 0 || tokens > budget.path("maximumTokens").asLong()
                    || calls + reservation.path("providerCalls").asLong() > budget.path("maximumProviderCalls").asLong()
                    || tokens + reservation.path("tokens").asLong() > budget.path("maximumTokens").asLong()
                    || !Instant.now().isBefore(Instant.parse(budget.path("deadline").asString()))) { stop = "BUDGET_OR_DEADLINE"; break; }
            if (target != null) target.requireMatches(plan.path("manifest"));
            var recorder = new Recorder(i); var cell = cells.get(i);
            try {
                executor.execute(cell, M8Bindings.modelInput(item(cell.caseId()), recorder.binding()), recorder);
                recorder.record.put("stopReason", "COMPLETED");
            } catch (Exception | AssertionError interrupted) {
                // Exception messages can contain response or credential material; preserve the class only.
                recorder.record.put("stopReason", "INTERRUPTED_" + interrupted.getClass().getSimpleName()); stop = "INTERRUPTED";
            } finally {
                if (!recorder.artifacts.containsKey("bindings")) recorder.put("bindings", recorder.binding());
                else require(recorder.artifacts.get("bindings").equals(recorder.binding()), "recorded binding differs from actual fixture");
                recorder.record.put("endedAt", Instant.now().toString()); recorder.persist();
            }
            executed++;
            var result = recorder.artifacts.getOrDefault("result", JSON.createObjectNode());
            calls += result.path("providerCalls").asLong(); tokens += result.path("tokens").asLong();
            if (calls > budget.path("maximumProviderCalls").asLong() || tokens > budget.path("maximumTokens").asLong()
                || result.path("providerCalls").asLong() > reservation.path("providerCalls").asLong()
                || result.path("tokens").asLong() > reservation.path("tokens").asLong()) { stop = "BUDGET_EXCEEDED"; break; }
            var raw = new TreeMap<>(recorder.artifacts); raw.put("bindings", recorder.binding());
            try {
                if (budget.path("stopOnHardGate").asBoolean() && !M8Scorer.score(item(cell.caseId()), raw).hardGates().isEmpty()) { stop = "HARD_GATE"; break; }
            } catch (IllegalArgumentException invalidEvidence) { stop = "INVALID_EVIDENCE"; break; }
            if (stop.equals("INTERRUPTED")) break;
        }
        writeNew(root.resolve("completion.json"), JSON.writeValueAsBytes(Map.of("stopPoint", stop, "executed", executed,
            "providerCalls", calls, "tokens", tokens, "endedAt", Instant.now().toString())));
        String seal = seal();
        JsonNode results = calculate(root, seal);
        writeReport(root, results);
        System.out.println("M8 " + plan.path("runId").asString() + ": " + results.path("status").asString()
            + "; planned=" + cells.size() + "; evidenceDigest=" + seal);
        return results;
    }

    public Path directory() { return root; }
    public String evidenceDigest() throws IOException { return Files.readString(root.resolve("evidence.sha256")); }

    private ObjectNode writeArtifact(String relative, byte[] bytes) throws IOException {
        Path file = M8ProtectedFiles.resolve(root, relative); writeNew(file, bytes);
        return (ObjectNode) JSON.valueToTree(Map.of("path", relative, "digest", digest(bytes), "bytes", bytes.length));
    }
    private static void writeNew(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent()); Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
    }
    private static byte[] classBytes(Class<?> type) throws IOException {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) { return Objects.requireNonNull(input).readAllBytes(); }
    }
    private static byte[] compiledBuild() throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            for (String directory : List.of("classes/java/main", "classes/java/test", "resources/main", "resources/test")) {
                Path root = Path.of("build").resolve(directory);
                try (var files = Files.walk(root)) {
                    for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
                        var entry = new java.util.zip.ZipEntry(directory + "/" + root.relativize(file).toString().replace('\\', '/'));
                        entry.setTime(0); zip.putNextEntry(entry); zip.write(Files.readAllBytes(file)); zip.closeEntry();
                    }
                }
            }
        }
        return bytes.toByteArray();
    }
    private String seal() throws IOException {
        var hashes = JSON.createObjectNode();
        try (var walk = Files.walk(root)) {
            for (var file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                hashes.put(relative, digest(Files.readAllBytes(file)));
            }
        }
        byte[] bytes = JSON.writeValueAsBytes(hashes); String digest = digest(bytes);
        writeNew(root.resolve("seal.json"), bytes); writeNew(root.resolve("evidence.sha256"), digest.getBytes(StandardCharsets.UTF_8));
        return digest;
    }

    static JsonNode readArtifact(Path root, JsonNode ref) throws IOException {
        Path file = M8ProtectedFiles.resolve(root, text(ref, "path"));
        byte[] bytes = Files.readAllBytes(file);
        require(bytes.length == ref.path("bytes").asLong() && digest(bytes).equals(text(ref, "digest")), "artifact bytes/digest mismatch");
        var value = JSON.readTree(bytes); rejectSensitive(value); return value;
    }

    public static JsonNode calculate(Path root, String expectedDigest) throws Exception {
        return calculate(root, expectedDigest, null, "");
    }

    public static JsonNode calculate(Path root, String expectedDigest, byte[] reviewBytes, String reviewDigest) throws Exception {
        M8ProtectedFiles.verify(root);
        byte[] sealBytes = Files.readAllBytes(root.resolve("seal.json"));
        require(expectedDigest.matches("[a-f0-9]{64}") && digest(sealBytes).equals(expectedDigest), "evidence seal digest mismatch");
        var seal = JSON.readTree(sealBytes);
        var integrity = new TreeMap<String, String>();
        for (var entry : seal.properties()) {
            Path file = M8ProtectedFiles.resolve(root, entry.getKey());
            if (!Files.exists(file)) integrity.put(entry.getKey(), "INCOMPLETE");
            else if (!digest(Files.readAllBytes(file)).equals(entry.getValue().asString())) integrity.put(entry.getKey(), "FAIL");
        }
        // Added records/artifacts cannot silently replace or enlarge the frozen experiment.
        try (var walk = Files.walk(root.resolve("cells"))) {
            for (var file : walk.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!seal.has(relative)) integrity.put(relative, "FAIL");
            }
        } catch (NoSuchFileException noCells) { /* A run interrupted before its first cell still has its whole denominator. */ }
        require(!integrity.containsKey("plan.json"), "frozen plan missing or changed");
        var plan = JSON.readTree(Files.readAllBytes(root.resolve("plan.json")));
        equal(plan, "scorer", SCORER); equal(plan, "dataClass", "SYNTHETIC");
        var manifest = plan.path("manifest"); M8EnvironmentContract.validate(manifest);
        var reviews = new HashMap<Cell, JsonNode>();
        if (reviewBytes != null) {
            require(digest(reviewBytes).equals(reviewDigest), "review bundle bytes/digest mismatch");
            JsonNode reviewBundle = JSON.readTree(reviewBytes);
            equal(reviewBundle, "version", "m8-answer-v1"); equal(reviewBundle, "runId", text(plan, "runId"));
            rejectSensitive(reviewBundle);
            var planned = new HashSet<Cell>();
            for (var c : plan.path("cells")) planned.add(JSON.treeToValue(c, Cell.class));
            for (var review : array(reviewBundle, "reviews")) {
                Cell cell = JSON.treeToValue(review.path("cell"), Cell.class);
                require(planned.contains(cell) && reviews.putIfAbsent(cell, review) == null, "duplicate/unknown review cell");
            }
        }
        var snapshots = plan.path("snapshots"); var snapshotValues = new HashMap<String, JsonNode>();
        for (var e : snapshots.properties()) snapshotValues.put(e.getKey(), readArtifact(root, e.getValue()));
        var dataset = snapshotValues.get("dataset.json"); var criteria = snapshotValues.get("criteria.json");
        validateDataset(dataset, snapshotValues.get("case-history.json"), path -> {
            try { return Files.readAllBytes(M8ProtectedFiles.resolve(root, snapshots.path(path).path("path").asString())); }
            catch (IOException e) { throw invalid("missing legacy snapshot"); }
        });
        M8RunContract.validateCriteria(criteria); validateRubric(snapshotValues.get("review-rubric.json"));
        require(snapshotValues.get("manifest").equals(manifest), "manifest snapshot mismatch");
        var source = snapshotValues.get("source-build");
        require(digest(JSON.writeValueAsBytes(source.path("code"))).equals(source.path("scorerDigest").asString()), "scorer identity mismatch");
        require(source.path("configDigest").equals(snapshots.path("manifest").path("digest")), "configuration identity mismatch");
        for (var entry : source.path("classes").properties()) {
            JsonNode ref = entry.getValue();
            byte[] bytes = Files.readAllBytes(M8ProtectedFiles.resolve(root, ref.path("path").asString()));
            require(bytes.length == ref.path("bytes").asLong() && digest(bytes).equals(ref.path("digest").asString()), "build snapshot mismatch");
            if (entry.getKey().startsWith("com/slotq/evaluation/") || entry.getKey().endsWith("ProviderProtocol") || entry.getKey().endsWith("ModelComparisonRunner")) {
                require(digest(classBytes(Class.forName(entry.getKey().replace('/', '.')))).equals(ref.path("digest").asString()), "scorer class version drift");
            }
        }
        var compiled = source.path("compiledBuild");
        byte[] buildBytes = Files.readAllBytes(M8ProtectedFiles.resolve(root, compiled.path("path").asString()));
        require(buildBytes.length == compiled.path("bytes").asLong() && digest(buildBytes).equals(source.path("buildDigest").asString()), "build identity mismatch");
        for (String name : CODE) {
            var ref = source.path("code").path(name);
            byte[] archived = Files.readAllBytes(M8ProtectedFiles.resolve(root, ref.path("path").asString()));
            require(digest(archived).equals(ref.path("digest").asString()), "scorer snapshot tampered");
            require(digest(normalized(archived)).equals(digest(normalized(Files.readAllBytes(Path.of("src/test/java/com/slotq/evaluation/" + name + ".java"))))), "scorer version drift; use original source");
        }
        var rows = JSON.createArrayNode(); var seen = new HashSet<Cell>(); var groupBindings = new HashMap<String, JsonNode>();
        var groupRuns = new HashMap<String, JsonNode>(); var runtimeOwners = new HashMap<String, String>();
        int index = 0;
        for (var cellNode : array(plan, "cells")) {
            Cell cell = JSON.treeToValue(cellNode, Cell.class); var item = item(cell.caseId());
            require(seen.add(cell) && cell.caseVersion().equals(text(item, "version")) && cell.repeat() > 0
                && strings(item, "lanes").contains(cell.lane()), "duplicate/version/lane-mismatched planned cell");
            var row = JSON.createObjectNode(); row.set("cell", cellNode); row.put("required", item.path("required").asBoolean());
            row.put("split", text(item, "split")).put("workload", text(item, "workload"));
            row.put("role", dataset.path("scope").path("actors").path(item.path("scope").path("actor").asString()).path("role").asString());
            row.put("expectedOutcome", item.path("oracle").path("proposal").path("outcome").asString());
            row.put("expectedInvocation", item.path("oracle").path("execution").path("invocation").asString());
            row.put("expectedEffect", item.path("oracle").path("execution").path("effect").asString());
            row.put("expectedDisposition", item.path("oracle").path("proposal").path("disposition").asString());
            row.put("expectedBoundary", item.path("scope").path("boundary").asString());
            row.set("axes", item.path("axes")); row.put("denominator", 1);
            var reasons = row.putArray("reasons"); var raw = new TreeMap<String, JsonNode>(); var paths = new HashSet<String>();
            String recordPath = "cells/" + index + "/record.json";
            Path recordFile = root.resolve(recordPath);
            var marks = new TreeMap<String, M8Scorer.Mark>(); var gates = new TreeSet<String>();
            if (!Files.exists(recordFile)) { marks.put("cell", new M8Scorer.Mark("INCOMPLETE", "계획 cell 미실행")); }
            else {
                try {
                    var record = JSON.readTree(Files.readAllBytes(recordFile)); row.set("evidence", record.path("artifacts"));
                    require(record.path("cell").equals(cellNode), "record cell identity mismatch");
                    if (record.has("rejectedArtifacts")) {
                        marks.put("artifact_policy", new M8Scorer.Mark("FAIL", "금지된 민감 원자료 차단; 원문 보관하지 않음"));
                        row.set("rejectedArtifacts", record.path("rejectedArtifacts"));
                    }
                    require(!record.path("endedAt").asString().isEmpty(), "interrupted cell");
                    if (!record.path("stopReason").asString().equals("COMPLETED")) marks.put("interrupted", new M8Scorer.Mark("INCOMPLETE", "실행 중단"));
                    for (var e : record.path("artifacts").properties()) {
                        paths.add(e.getValue().path("path").asString());
                        try { raw.put(e.getKey(), readArtifact(root, e.getValue())); }
                        catch (NoSuchFileException missing) { marks.put(e.getKey(), new M8Scorer.Mark("INCOMPLETE", "필수 원자료 누락")); }
                        catch (IllegalArgumentException tampered) { marks.put(e.getKey(), new M8Scorer.Mark("FAIL", "원자료 bytes/digest 불일치")); }
                    }
                    var bindingRef = plan.path("bindings").get(index); paths.add(bindingRef.path("path").asString());
                    var initialBinding = readArtifact(root, bindingRef);
                    var binding = raw.getOrDefault("bindings", initialBinding);
                    require(M8Bindings.preservesAssignments(initialBinding, binding), "initial fixture rebound");
                    raw.put("bindings", binding);
                    M8Bindings.validate(binding, new M8Bindings.CellIdentity(text(plan, "runId"), cell), item);
                    var previous = groupBindings.putIfAbsent(groupKey(cell), binding);
                    require(previous == null || M8Bindings.preservesAssignments(previous, binding), "fixture group rebound");
                    groupBindings.put(groupKey(cell), binding);
                    var required = new HashSet<>(M8RunContract.requiredEvidence(item));
                    required.removeAll(Set.of("bindings", "manifest", "criteria", "source-build"));
                    for (String id : required) if (!raw.containsKey(id)) marks.put("artifact_" + id, new M8Scorer.Mark("INCOMPLETE", "필수 원자료 미확보"));
                    var score = M8Scorer.score(item, raw); marks.putAll(score.checks()); gates.addAll(score.hardGates());
                    if (raw.getOrDefault("result", JSON.createObjectNode()).path("usageUnknown").asBoolean()) {
                        marks.put("usage", new M8Scorer.Mark("INCOMPLETE", "usage 미관측; 예산 예약 유지"));
                    }
                    row.set("timing", JSON.valueToTree(Map.of("startedAt", record.path("startedAt").asString(), "endedAt", record.path("endedAt").asString(), "stopPoint", record.path("stopReason").asString())));
                    if (reviews.containsKey(cell)) raw.put("review", reviews.get(cell));
                    if (raw.containsKey("review")) row.set("review", raw.get("review"));
                    if (raw.getOrDefault("result", JSON.createObjectNode()).path("runtime").has("runId") && raw.containsKey("actor")) {
                        var runtime = raw.get("result").path("runtime");
                        String runtimeId = runtime.path("runId").asString(); UUID.fromString(runtimeId);
                        var frame = JSON.createObjectNode(); frame.set("runtimeRunId", runtime.path("runId"));
                        frame.set("delegationId", raw.get("actor").path("delegationId")); frame.set("originalCredentialId", raw.get("actor").path("originalCredentialId"));
                        frame.set("plan", raw.getOrDefault("flow-plan", JSON.createObjectNode()));
                        frame.set("admittedAt", runtime.path("budget").path("admittedAt")); frame.set("deadline", runtime.path("budget").path("deadline"));
                        var previousFrame = groupRuns.putIfAbsent(groupKey(cell), frame);
                        String previousGroup = runtimeOwners.putIfAbsent(runtimeId, groupKey(cell));
                        boolean sameRun = (previousFrame == null || previousFrame.equals(frame)) && (previousGroup == null || previousGroup.equals(groupKey(cell)));
                        marks.put("run_continuity", new M8Scorer.Mark(sameRun ? "PASS" : "FAIL", sameRun ? "원래 Run·위임·Plan·deadline 보존" : "Run 재사용/교체 또는 원래 실행 경계 불일치"));
                    }
                    if (raw.containsKey("answer") && raw.getOrDefault("result", JSON.createObjectNode()).path("proposal").isObject()) {
                        boolean same = raw.get("answer").equals(raw.get("result").path("proposal"));
                        marks.put("answer_identity", new M8Scorer.Mark(same ? "PASS" : "FAIL", same ? "모델 제안/최종 답변 원자료 일치" : "답변 원자료 불일치"));
                    }
                    row.set("observedExecution", raw.getOrDefault("result", raw.getOrDefault("mutation-observation", JSON.createObjectNode())).path("execution"));
                    row.put("observationKind", raw.getOrDefault("result", raw.getOrDefault("mutation-observation", JSON.createObjectNode())).path("observationKind").asString("NOT_EXECUTED"));
                    row.set("boundaryRejection", raw.getOrDefault("result", JSON.createObjectNode()).path("boundaryRejection"));
                    boolean finalProposal = raw.getOrDefault("result", JSON.createObjectNode()).path("proposal").isObject();
                    row.put("modelProposalObserved", finalProposal || raw.getOrDefault("proposal-stage", JSON.createObjectNode()).path("output").isObject());
                    row.put("finalModelProposalObserved", finalProposal);
                    review(item, raw, cellNode, text(plan, "runId"), record, marks);
                } catch (NoSuchFileException missing) { marks.put("binding", new M8Scorer.Mark("INCOMPLETE", "binding 미확보")); }
                catch (IllegalArgumentException invalid) { marks.put("contract", new M8Scorer.Mark("FAIL", "자료/fixture/binding 계약 불일치")); }
            }
            paths.add(recordPath);
            for (String check : M8Scorer.requiredChecks(item)) marks.putIfAbsent(check, new M8Scorer.Mark("INCOMPLETE", "예정 평가 관측 미확보"));
            for (var violation : integrity.entrySet()) {
                if (paths.contains(violation.getKey()) || violation.getKey().startsWith("cells/" + index + "/")
                    || !violation.getKey().startsWith("cells/") && !violation.getKey().startsWith("bindings/")) {
                    marks.put("integrity_" + violation.getKey(), new M8Scorer.Mark(violation.getValue(), "실제 bytes 무결성/자료 누락"));
                }
            }
            String automatic = M8Scorer.combine(marks.entrySet().stream().filter(e -> !e.getKey().equals("human_review")).map(e -> e.getValue().status()).toList());
            row.put("automaticStatus", automatic); row.set("checks", JSON.valueToTree(marks)); row.set("hardGateViolations", JSON.valueToTree(gates));
            row.set("axisResults", axisResults(marks));
            marks.forEach((name, mark) -> { if (!mark.status().equals("PASS")) reasons.add(name + ": " + mark.reason()); });
            var statuses = new ArrayList<>(marks.values().stream().map(M8Scorer.Mark::status).toList());
            statuses.add("INCOMPLETE"); reasons.add("M8 본 검증 기준 미확정; 결정적 회귀는 출시 PASS가 아님");
            String status = !gates.isEmpty() ? "FAIL" : M8Scorer.combine(statuses);
            row.put("status", status); rows.add(row); index++;
            row.set("record", contractRecord(plan, cell, item, row, source, snapshots, index - 1));
        }
        var result = JSON.createObjectNode();
        result.put("scorer", SCORER).put("runId", text(plan, "runId")).put("evidenceDigest", expectedDigest)
            .put("criteriaState", "PENDING").put("plannedCells", rows.size()).put("releaseQualification", false);
        result.put("reviewDigest", reviewDigest);
        result.set("rows", rows); result.set("cohorts", aggregate(rows));
        String status = M8Scorer.combine(rows.valueStream().map(r -> r.path("status").asString()).toList());
        if (integrity.containsValue("FAIL")) status = "FAIL";
        result.put("status", status); result.set("integrity", JSON.valueToTree(integrity));
        return JSON.valueToTree(canonical(result));
    }

    private static JsonNode contractRecord(JsonNode plan, Cell cell, JsonNode item, JsonNode row, JsonNode source, JsonNode snapshots, int index) {
        var record = JSON.createObjectNode();
        record.put("version", "1.0.0").put("runId", text(plan, "runId")).put("caseId", cell.caseId()).put("caseVersion", cell.caseVersion())
            .put("repeat", cell.repeat()).put("model", cell.model()).put("lane", cell.lane()).put("phase", "REGRESSION")
            .put("status", row.path("status").asString()).put("reason", "자동 검사·원자료·검토·사전 기준의 결합 판정")
            .put("denominatorUnit", "PLANNED_CASE_REPEAT_MODEL_LANE");
        record.putArray("exclusions"); record.set("axes", item.path("axes")); record.putArray("thresholdIds").add("quality").add("security");
        record.set("hardGateViolations", row.path("hardGateViolations"));
        var identity = record.putObject("identity");
        identity.set("sourceRevision", source.path("sourceRevision")); identity.set("sourceDirty", source.path("sourceDirty"));
        for (String key : List.of("buildDigest", "configDigest", "promptDigest", "schemaDigest", "scorerDigest")) identity.set(key, source.path(key));
        identity.set("datasetDigest", snapshots.path("dataset.json").path("digest")); identity.set("criteriaDigest", snapshots.path("criteria.json").path("digest"));
        identity.put("oracleDigest", digest(JSON.writeValueAsBytes(read("dataset.json").path("cases").valueStream().map(c -> c.path("oracle")).toList())));
        identity.put("supportDigest", digest(JSON.writeValueAsBytes(plan.path("manifest").path("versions").path("support"))));
        identity.set("bindingsDigest", row.path("evidence").has("bindings") ? row.path("evidence").path("bindings").path("digest") : plan.path("bindings").get(index).path("digest"));
        identity.set("startedAt", row.path("timing").has("startedAt") ? row.path("timing").path("startedAt") : plan.path("frozenAt"));
        identity.set("endedAt", row.path("timing").has("endedAt") && !row.path("timing").path("endedAt").asString().isEmpty() ? row.path("timing").path("endedAt") : identity.path("startedAt"));
        identity.put("stopPoint", row.path("timing").path("stopPoint").asString("NOT_EXECUTED")).put("parentRunId", "").put("originalEvidenceDigest", "");
        var evidence = record.putObject("evidence"); evidence.set("required", JSON.valueToTree(new TreeSet<>(M8RunContract.requiredEvidence(item))));
        var provided = evidence.putArray("provided");
        var refs = new TreeMap<String, JsonNode>(); row.path("evidence").properties().forEach(e -> refs.put(e.getKey(), e.getValue()));
        for (String key : List.of("manifest", "source-build")) refs.put(key, snapshots.path(key));
        refs.put("criteria", snapshots.path("criteria.json")); refs.putIfAbsent("bindings", plan.path("bindings").get(index));
        refs.forEach((id, ref) -> provided.addObject().put("id", id).put("path", "build/m8-evidence/" + plan.path("runId").asString() + "/" + ref.path("path").asString())
            .put("digest", ref.path("digest").asString()).put("kind", cell.lane()));
        var review = record.putObject("review"); review.put("state", item.path("humanReview").asBoolean() ? "PENDING" : "NOT_REQUIRED")
            .put("rubricVersion", item.path("humanReview").asBoolean() ? "m8-answer-v1" : "")
            .put("requestDigest", "").put("answerDigest", "").put("reviewer", "").put("reviewedAt", "");
        if (row.path("checks").path("human_review").path("status").asString().equals("PASS")) {
            review.put("state", "APPROVED");
            for (String key : List.of("requestDigest", "answerDigest", "reviewer", "reviewedAt")) review.set(key, row.path("review").path(key));
        } else if (row.path("review").path("state").asString().equals("REJECTED")) review.put("state", "REJECTED");
        M8RunContract.validateRecord(record, item, plan.path("manifest"), read("criteria.json"));
        return record;
    }

    private static void review(JsonNode item, Map<String, JsonNode> raw, JsonNode cell, String runId, JsonNode record, Map<String, M8Scorer.Mark> checks) {
        if (!item.path("humanReview").asBoolean()) return;
        var review = raw.get("review");
        if (review == null || review.path("state").asString().equals("PENDING")) {
            checks.put("human_review", new M8Scorer.Mark("INCOMPLETE", "한국어 답변 의미 검토 대기")); return;
        }
        boolean bound = review.path("runId").asString().equals(runId) && review.path("cell").equals(cell)
            && review.path("rubricVersion").asString().equals("m8-answer-v1")
            && review.path("requestDigest").equals(record.path("artifacts").path("request").path("digest"))
            && review.path("answerDigest").equals(record.path("artifacts").path("answer").path("digest"))
            && review.path("reviewer").isString() && !review.path("reviewer").asString().isBlank();
        try { bound &= !Instant.parse(review.path("reviewedAt").asString()).isBefore(Instant.parse(record.path("startedAt").asString())); }
        catch (RuntimeException invalidTime) { bound = false; }
        Set<String> rubric = strings(read("review-rubric.json"), "required");
        bound &= review.path("findings").isObject() && review.path("findings").propertyNames().equals(rubric);
        boolean approved = review.path("state").asString().equals("APPROVED")
            && review.path("findings").valueStream().allMatch(n -> n.isBoolean() && n.asBoolean());
        String state = review.path("state").asString();
        checks.put("human_review", new M8Scorer.Mark(state.equals("REJECTED") ? "FAIL" : !bound ? "INCOMPLETE" : approved ? "PASS" : "FAIL", !bound ? "검토 identity/digest/rubric 불일치" : approved ? "의미 검토 승인" : "의미 검토 거부"));
    }

    static JsonNode aggregate(JsonNode rows) {
        var groups = new TreeMap<String, ObjectNode>();
        for (var row : rows) {
            var metrics = union(row.path("checks").propertyNames(), Set.of("all"));
            for (var axis : row.path("axes")) metrics.add("axis." + axis.asString());
            for (String metric : metrics) {
            String key = JSON.writeValueAsString(List.of(row.path("cell").path("repeat"), row.path("cell").path("model"), row.path("observationKind").asString("NOT_EXECUTED"),
                row.path("cell").path("lane"), row.path("role"), row.path("workload"), row.path("split"), row.path("expectedOutcome"), row.path("expectedInvocation"), row.path("expectedEffect"),
                row.path("expectedDisposition"), row.path("expectedBoundary"), row.path("boundaryRejection").path("layer").asString("NONE"), metric));
            var group = groups.computeIfAbsent(key, k -> {
                var n = JSON.createObjectNode(); n.put("denominator", 0);
                var dimensions = n.putObject("dimensions");
                for (String name : List.of("repeat", "model", "lane")) dimensions.set(name, row.path("cell").path(name));
                for (String name : List.of("role", "workload", "split", "expectedOutcome", "expectedInvocation", "expectedEffect", "expectedDisposition", "expectedBoundary")) {
                    dimensions.set(name, row.path(name));
                }
                dimensions.put("observationKind", row.path("observationKind").asString("NOT_EXECUTED"));
                dimensions.put("rejectionLayer", row.path("boundaryRejection").path("layer").asString("NONE"));
                dimensions.put("metric", metric);
                for (String status : M8RunContract.STATUSES) n.put(status, 0);
                return n;
            });
            group.put("denominator", group.path("denominator").asInt() + 1);
            String status = metric.equals("all") ? row.path("status").asString() : metric.startsWith("axis.")
                ? row.path("axisResults").path(metric.substring(5)).path("status").asString() : row.path("checks").path(metric).path("status").asString();
            group.put(status, group.path(status).asInt() + 1);
            }
        }
        return JSON.valueToTree(groups);
    }
    private static JsonNode axisResults(Map<String, M8Scorer.Mark> marks) {
        var axes = JSON.createObjectNode();
        for (String axis : new TreeSet<>(AXES)) {
            var statuses = new ArrayList<String>();
            for (var entry : marks.entrySet()) {
                String name = entry.getKey();
                boolean belongs = switch (axis) {
                    case "tool_selection" -> Set.of("model_tool_selection", "proposal_schema").contains(name);
                    case "parameter_extraction" -> name.equals("parameter_extraction");
                    case "retrieval_quality" -> name.startsWith("retrieval_") || name.startsWith("answer_") && !name.equals("answer_identity");
                    case "execution_success" -> name.startsWith("execution_");
                    case "forbidden_action" -> name.startsWith("forbidden_") || name.equals("hard_gate");
                    default -> false;
                };
                if (belongs) statuses.add(entry.getValue().status());
            }
            axes.putObject(axis).put("status", statuses.isEmpty() ? "NOT_APPLICABLE" : M8Scorer.combine(statuses));
        }
        return axes;
    }
    private static Set<String> union(Collection<String> a, Set<String> b) { var set = new TreeSet<>(a); set.addAll(b); return set; }
    private static String groupKey(Cell cell) { return M8Bindings.group(item(cell.caseId())) + ":" + cell.repeat() + ":" + cell.model() + ":" + cell.lane(); }
    private static String git(String... arguments) throws Exception {
        var args = new ArrayList<>(List.of("git", "-c", "safe.directory=C:/dev/slotq")); args.addAll(List.of(arguments));
        var process = new ProcessBuilder(args).redirectErrorStream(true).start(); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        require(process.waitFor() == 0, "source identity unavailable"); return output;
    }
    static void writeReport(Path destination, JsonNode results) throws IOException {
        Files.createDirectories(destination);
        writeNew(destination.resolve("results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(results));
        StringBuilder summary = new StringBuilder("M8 공통 평가 결과: " + results.path("status").asString() + "\n\n계획 cell: " + results.path("plannedCells").asInt()
            + " / 기준: PENDING / 출시 판정: 수행하지 않음\n\n| 사례 | 반복 | 모델 | lane | 자동 검사 | 최종 | 사유 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
        for (var row : results.path("rows")) {
            var cell = row.path("cell"); summary.append("| ").append(cell.path("caseId").asString()).append(" | ").append(cell.path("repeat").asInt())
                .append(" | ").append(cell.path("model").asString()).append(" | ").append(cell.path("lane").asString()).append(" | ")
                .append(row.path("automaticStatus").asString()).append(" | ").append(row.path("status").asString()).append(" | ")
                .append(JSON.writeValueAsString(row.path("reasons")).replace("|", "/")).append(" |\n");
        }
        writeNew(destination.resolve("summary.ko.md"), summary.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        String runId = System.getProperty("slotq.m8.runId", "");
        String expected = System.getProperty("slotq.m8.digest", "");
        Path root = M8ProtectedFiles.root(runId);
        String reviews = System.getProperty("slotq.m8.reviews", "");
        String reviewDigest = System.getProperty("slotq.m8.reviewDigest", "");
        byte[] reviewBytes = reviews.isEmpty() ? null : Files.readAllBytes(M8ProtectedFiles.resolve(root, reviews));
        JsonNode results = calculate(root, expected, reviewBytes, reviewDigest);
        String calculationId = "recalc-" + UUID.randomUUID(); Path destination = root.resolve("recalculations/" + calculationId);
        writeReport(destination, results);
        if (reviewBytes != null) writeNew(destination.resolve("review.json"), reviewBytes);
        writeNew(destination.resolve("lineage.json"), JSON.writeValueAsBytes(Map.of("calculationId", calculationId, "parentRunId", runId,
            "originalEvidenceDigest", expected, "reviewDigest", reviewDigest, "kind", "RECALCULATION", "createdAt", Instant.now().toString())));
        System.out.println("M8 오프라인 재계산: " + results.path("status").asString() + "; " + destination);
    }
}
