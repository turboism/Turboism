package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpWriteResults.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.command.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Document identity discovery and guarded NEW_MODEL dispatch; no file or generic UI automation. */
final class McpDocumentLifecycleDomain {
    static final String READ = "turboism.documents.read",
            CREATE = "turboism.documents.new_model",
            RESULT = "turboism.documents.new_result";
    static final long TTL = java.time.Duration.ofMinutes(5).toNanos();
    private final CubismFacade cubism;
    private final EditorCommandService commands;
    private final McpExecutionBridge execution;
    private final Supplier<Map<String, Object>> authoring;
    private final LongSupplier clock;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final Map<Key, Operation> operations = new HashMap<>();

    McpDocumentLifecycleDomain(
            CubismFacade cubism,
            EditorCommandService commands,
            McpExecutionBridge execution,
            Supplier<Map<String, Object>> authoring) {
        this(cubism, commands, execution, authoring, System::nanoTime);
    }

    McpDocumentLifecycleDomain(
            CubismFacade cubism,
            EditorCommandService commands,
            McpExecutionBridge execution,
            Supplier<Map<String, Object>> authoring,
            LongSupplier clock) {
        this.cubism = Objects.requireNonNull(cubism);
        this.commands = Objects.requireNonNull(commands);
        this.execution = Objects.requireNonNull(execution);
        this.authoring = Objects.requireNonNull(authoring);
        this.clock = Objects.requireNonNull(clock);
    }

    McpToolCatalog tools() {
        var create = McpRegisteredTool.typed(
                McpDocumentLifecycleSchemas.definition(CREATE),
                McpOperationEffect.EXTERNAL_SIDE_EFFECT,
                McpExecutionAffinity.UI_THREAD,
                false,
                execution,
                this::createOnUi);
        create = create.withPublicHandler(this::create);
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpDocumentLifecycleSchemas.definition(READ),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        execution,
                        this::read),
                create,
                McpRegisteredTool.typed(
                        McpDocumentLifecycleSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result)));
    }

    private Map<String, Object> read(Map<String, Object> args) {
        try {
            fields(args);
            var state = capture();
            if (!state.equals(capture())) throw new Stale();
            String id = UUID.randomUUID().toString();
            synchronized (snapshots) {
                prune();
                while (snapshots.size() >= 16)
                    snapshots.remove(snapshots.keySet().iterator().next());
                snapshots.put(id, new Snapshot(session(), state, clock.getAsLong()));
            }
            return envelope(Map.of(
                    "ok",
                    true,
                    "snapshotId",
                    id,
                    "state",
                    state,
                    "stateHash",
                    digest(state),
                    "snapshotTtlSeconds",
                    300,
                    "identityScope",
                    "DECLARED_DOCUMENT_METADATA_AND_ACTIVE_AUTHORING_BINDING",
                    "writeAdmission",
                    "NOT_CHECKED"));
        } catch (RuntimeException e) {
            return error(code(e));
        }
    }

    private Map<String, Object> create(Map<String, Object> args) {
        final Operation op;
        try {
            op = admit(args);
        } catch (RuntimeException e) {
            return error(code(e));
        }
        synchronized (operations) {
            if (op.claimed) return envelope(op.output);
            op.claimed = true;
        }
        try {
            return execution.ui(() -> {
                run(op);
                return envelope(op.output);
            });
        } catch (RuntimeException e) {
            synchronized (operations) {
                if (op.output.get("outcome").equals("QUEUED"))
                    op.output = output(op.key.id, "NOT_APPLIED", "CANCELED_BEFORE_HOST", null, null, false);
                else if (op.output.get("outcome").equals("RUNNING"))
                    op.output = output(op.key.id, "OUTCOME_UNKNOWN", "EXECUTION_LOST", null, null, false);
                return envelope(op.output);
            }
        }
    }

    private Map<String, Object> createOnUi(Map<String, Object> args) {
        try {
            var op = admit(args);
            run(op);
            return envelope(op.output);
        } catch (RuntimeException e) {
            return error(code(e));
        }
    }

    private Operation admit(Map<String, Object> args) {
        fields(args, "snapshotId", "operationId");
        String operation = text(args.get("operationId"), 128);
        String id = text(args.get("snapshotId"), 128);
        String fingerprint = digest(args);
        var key = new Key(session(), operation);
        if (key.session.isBlank()) throw new Rejected("SESSION_REQUIRED");
        synchronized (operations) {
            var old = operations.get(key);
            if (old != null) {
                if (!old.fingerprint.equals(fingerprint)) throw new Rejected("OPERATION_ID_CONFLICT");
                return old;
            }
            if (operations.size() >= 128) throw new Rejected("RESULT_STORE_FULL");
            Snapshot snapshot;
            synchronized (snapshots) {
                prune();
                snapshot = snapshots.get(id);
            }
            if (snapshot == null || !snapshot.session.equals(key.session)) throw new Rejected("SNAPSHOT_NOT_FOUND");
            var op = new Operation(key, fingerprint, snapshot);
            operations.put(key, op);
            return op;
        }
    }

    private void run(Operation op) {
        synchronized (operations) {
            if (!op.output.get("outcome").equals("QUEUED")) return;
            op.output = output(op.key.id, "RUNNING", "RUNNING", null, null, false);
        }
        boolean dispatched = false;
        try {
            if (clock.getAsLong() - op.snapshot.created >= TTL) throw new Stale();
            if (!op.snapshot.state.equals(capture())) throw new Stale();
            checkCapacity(op.snapshot.state);
            if (!commands.isAvailable() || !commands.available().contains(EditorCommand.NEW_MODEL))
                throw new Rejected("UNAVAILABLE");
            if (clock.getAsLong() - op.snapshot.created >= TTL || !op.snapshot.state.equals(capture()))
                throw new Stale();
            McpRequestRegistry.throwIfCancelled();
            dispatched = true;
            var result = commands.execute(EditorCommand.NEW_MODEL);
            if (result == null || !EditorCommand.NEW_MODEL.id().equals(result.commandId())) {
                finish(op, "OUTCOME_UNKNOWN", "RESULT_COMMAND_MISMATCH", null, null, false);
                return;
            }
            if (!result.executed()) {
                finish(
                        op,
                        result.status() == EditorCommandResult.Status.FAILED ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                        result.status().name(),
                        result.status().name(),
                        null,
                        false);
                return;
            }
            Map<String, Object> after;
            try {
                after = capture();
                if (!after.equals(capture())) throw new Stale();
            } catch (RuntimeException e) {
                finish(
                        op,
                        "OUTCOME_UNKNOWN",
                        "POST_COMMAND_READ_FAILED",
                        result.status().name(),
                        null,
                        false);
                return;
            }
            boolean ready = newTarget(op.snapshot.state, after);
            finish(
                    op,
                    ready ? "READY" : "EXECUTED_NOT_READY",
                    ready ? "NEW_ACTIVE_MODEL_OBSERVED" : "NO_CONFIRMED_NEW_ACTIVE_MODEL",
                    result.status().name(),
                    after,
                    ready);
        } catch (RuntimeException e) {
            finish(
                    op,
                    dispatched ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    dispatched ? "HOST_OUTCOME_UNKNOWN" : code(e),
                    null,
                    null,
                    false);
        }
    }

    @SuppressWarnings("unchecked")
    static void checkCapacity(Map<String, Object> state) {
        if (state.get("project") == null) {
            if (state.get("activeDocument") != null) throw new Rejected("INCOMPLETE_DOCUMENT_INVENTORY");
            return;
        }
        var project = (Map<String, Object>) state.get("project");
        var contents = (List<Map<String, Object>>) project.get("contents");
        if (((List<?>) project.get("documents")).size() >= 128
                || contents.size() >= 128
                || contents.stream()
                                .mapToInt(c -> ((List<?>) c.get("documentIds")).size())
                                .sum()
                        >= 256) throw new Budget();
    }

    @SuppressWarnings("unchecked")
    private static boolean newTarget(Map<String, Object> before, Map<String, Object> after) {
        if (!(after.get("activeDocument") instanceof Map<?, ?> active)
                || !"MODEL".equals(active.get("kind"))
                || after.get("authoringState") == null) return false;
        var oldProject = (Map<String, Object>) before.get("project");
        var newProject = (Map<String, Object>) after.get("project");
        if (oldProject == null && before.get("activeDocument") != null) return false;
        if (!Boolean.TRUE.equals(after.get("hostObserved"))) return false;
        if (newProject == null
                || oldProject != null && !Objects.equals(oldProject.get("projectId"), newProject.get("projectId")))
            return false;
        var oldDocs = oldProject == null
                ? List.<Map<String, Object>>of()
                : (List<Map<String, Object>>) oldProject.get("documents");
        var newDocs = (List<Map<String, Object>>) newProject.get("documents");
        if (newDocs.size() != oldDocs.size() + 1
                || oldDocs.stream()
                        .anyMatch(d -> d.get("documentId").equals(active.get("documentId"))
                                || Objects.equals(d.get("modelId"), active.get("modelId")))) return false;
        if (!newDocs.stream()
                .filter(d -> !d.get("documentId").equals(active.get("documentId")))
                .toList()
                .equals(oldDocs)) return false;
        if (before.get("activeDocument") instanceof Map<?, ?> previous
                && previous.get("documentId").equals(active.get("documentId"))) return false;
        if (oldProject != null) {
            var oldContents = (List<Map<String, Object>>) oldProject.get("contents");
            var newContents = (List<Map<String, Object>>) newProject.get("contents");
            for (var old : oldContents) if (!newContents.contains(old)) return false;
        }
        return true;
    }

    Map<String, Object> expectedSnapshot(String id) {
        synchronized (snapshots) {
            prune();
            var snapshot = snapshots.get(id);
            if (snapshot == null || !snapshot.session.equals(session()))
                throw new IllegalArgumentException("Snapshot unavailable");
            return snapshot.state;
        }
    }

    Map<String, Object> capture() {
        try {
            return captureNative();
        } catch (NoSuchMethodError | AbstractMethodError e) {
            throw new UnsupportedOperationException("Document metadata reader unavailable", e);
        }
    }

    private Map<String, Object> captureNative() {
        var project = cubism.activeProject();
        var active = cubism.activeDocument();
        var model = cubism.activeModel();
        if (model.isPresent()
                && (active.isEmpty()
                        || !active.get().isModelDocument()
                        || active.get().model().isEmpty()
                        || !model.get()
                                .modelId()
                                .equals(active.get().model().get().modelId()))) throw new Stale();
        if (active.isPresent()
                && active.get().model().isPresent()
                && (model.isEmpty()
                        || !active.get()
                                .model()
                                .get()
                                .modelId()
                                .equals(model.get().modelId()))) throw new Stale();
        Map<String, Object> projectedProject = null;
        if (project.isPresent()) {
            var p = project.get();
            if (p.documents().size() > 128 || p.contents().size() > 128) throw new Budget();
            var ids = new HashSet<String>();
            var models = new HashSet<String>();
            var docs = new ArrayList<Map<String, Object>>();
            for (var d : p.documents()) {
                var row = document(d);
                if (!ids.add(d.documentId())) throw new Stale();
                if (d.model().isPresent() && !models.add(d.model().get().modelId())) throw new Stale();
                docs.add(row);
            }
            if (active.isPresent() && !docs.contains(document(active.get()))) throw new Stale();
            var contents = new ArrayList<Map<String, Object>>();
            ids.clear();
            int associations = 0;
            for (var c : p.contents()) {
                if (!ids.add(c.contentId())) throw new Stale();
                associations += c.documentIds().size();
                if (associations > 256) throw new Budget();
                var docIds = c.documentIds().stream().map(id -> text(id, 4096)).toList();
                if (new HashSet<>(docIds).size() != docIds.size()) throw new Stale();
                contents.add(Map.of(
                        "contentId",
                        text(c.contentId(), 4096),
                        "name",
                        text(c.name(), 4096),
                        "kind",
                        c.kind().name(),
                        "documentIds",
                        docIds));
            }
            projectedProject = Map.of(
                    "projectId",
                    text(p.projectId(), 4096),
                    "name",
                    text(p.name(), 4096),
                    "documents",
                    List.copyOf(docs),
                    "contents",
                    List.copyOf(contents));
        }
        Map<String, Object> binding = null;
        if (model.isPresent())
            try {
                var candidate = Map.copyOf(authoring.get());
                McpParameterStructure.fields(
                        candidate,
                        "documentId",
                        "modelId",
                        "historyDocumentBinding",
                        "historyManagerBinding",
                        "historyGeneration",
                        "historyRevision");
                for (String key : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
                    text(candidate.get(key), 4096);
                for (String key : List.of("historyGeneration", "historyRevision")) {
                    if (!(candidate.get(key) instanceof Long n) || n < 0) throw new IllegalArgumentException();
                }
                if (!candidate.get("modelId").equals(model.get().modelId())
                        || !candidate
                                .get("documentId")
                                .equals(active.orElseThrow().documentId())) throw new Stale();
                binding = candidate;
            } catch (Stale e) {
                throw e;
            } catch (RuntimeException e) {
                /* Read inventory remains useful when authoring/history is unavailable. */
            }
        var result = new LinkedHashMap<String, Object>();
        result.put("hostObserved", cubism.isHostPresent());
        result.put("project", projectedProject);
        result.put(
                "activeDocument",
                active.map(McpDocumentLifecycleDomain::document).orElse(null));
        result.put("authoringState", binding);
        var frozen = Collections.unmodifiableMap(result);
        if (McpParameterStructure.bytes(frozen) > 192 * 1024) throw new Budget();
        return frozen;
    }

    private static Map<String, Object> document(DocumentSnapshot d) {
        var row = new LinkedHashMap<String, Object>();
        row.put("documentId", text(d.documentId(), 4096));
        row.put("name", text(d.name(), 4096));
        row.put("kind", d.kind().name());
        row.put("contentId", d.contentId().map(v -> text(v, 4096)).orElse(null));
        row.put("modelId", d.model().map(m -> text(m.modelId(), 4096)).orElse(null));
        return Collections.unmodifiableMap(row);
    }

    private Map<String, Object> result(Map<String, Object> args) {
        fields(args, "operationId");
        String id = text(args.get("operationId"), 128);
        synchronized (operations) {
            var op = operations.get(new Key(session(), id));
            return envelope(op == null ? output(id, "OUTCOME_UNKNOWN", "NOT_FOUND", null, null, false) : op.output);
        }
    }

    private void finish(
            Operation op, String outcome, String code, String status, Map<String, Object> state, boolean ready) {
        synchronized (operations) {
            op.output = output(op.key.id, outcome, code, status, state, ready);
        }
    }

    private static Map<String, Object> output(
            String id, String outcome, String code, String status, Map<String, Object> state, boolean ready) {
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", Set.of("QUEUED", "RUNNING", "READY").contains(outcome));
        out.put("operationId", id);
        out.put("outcome", outcome);
        out.put("code", code);
        out.put("nativeStatus", status);
        out.put("afterState", state);
        out.put("authoringReady", ready);
        out.put("existingModelContentsVerified", false);
        out.put("persistenceVerified", false);
        out.put("retrySafe", false);
        return Collections.unmodifiableMap(out);
    }

    private void prune() {
        long now = clock.getAsLong();
        snapshots.values().removeIf(s -> now - s.created >= TTL);
    }

    private static Map<String, Object> error(String code) {
        return envelope(Map.of("ok", false, "error", code));
    }

    private static String code(RuntimeException e) {
        if (e instanceof Rejected r) return r.code;
        if (e instanceof Stale) return "STALE_STATE";
        if (e instanceof Budget) return "BUDGET_EXCEEDED";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof java.util.concurrent.CancellationException) return "CANCELED_BEFORE_HOST";
        if (e instanceof UnsupportedOperationException) return "UNAVAILABLE";
        if (e instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        return "READ_OR_ADMISSION_FAILED";
    }

    private static void fields(Map<String, Object> args, String... fields) {
        McpParameterStructure.fields(args, fields);
    }

    private static String text(Object v, int max) {
        return McpParameterStructure.text(v, max);
    }

    private static String session() {
        return McpRequestRegistry.currentSessionId();
    }

    private record Snapshot(String session, Map<String, Object> state, long created) {}

    private record Key(String session, String id) {}

    private static final class Operation {
        final Key key;
        final String fingerprint;
        final Snapshot snapshot;
        boolean claimed;
        volatile Map<String, Object> output;

        Operation(Key key, String fingerprint, Snapshot snapshot) {
            this.key = key;
            this.fingerprint = fingerprint;
            this.snapshot = snapshot;
            output = output(key.id, "QUEUED", "QUEUED", null, null, false);
        }
    }

    private static final class Stale extends IllegalStateException {}

    private static final class Budget extends IllegalArgumentException {}

    private static final class Rejected extends IllegalStateException {
        final String code;

        Rejected(String code) {
            this.code = code;
        }
    }
}
