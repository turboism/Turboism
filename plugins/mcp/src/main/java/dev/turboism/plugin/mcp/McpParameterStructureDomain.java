package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpParameterStructure.*;
import static dev.turboism.plugin.mcp.McpWriteResults.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Session-bound parameter organization plans; owns a native authoring transaction. */
final class McpParameterStructureDomain {
    static final String READ = "turboism.parameters.structure.read",
            PLAN = "turboism.parameters.structure.plan",
            APPLY = "turboism.parameters.structure.apply",
            RESULT = "turboism.parameters.structure.result";
    static final long TTL = java.time.Duration.ofMinutes(5).toNanos();
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final LongSupplier clock;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, Plan> plans = new LinkedHashMap<>();
    private final Map<Key, Operation> operations = new HashMap<>();

    McpParameterStructureDomain(
            CubismFacade cubism, McpExecutionBridge execution, Supplier<McpStoredAuthoringDomain.Context> context) {
        this(cubism, execution, context, System::nanoTime);
    }

    McpParameterStructureDomain(
            CubismFacade cubism,
            McpExecutionBridge execution,
            Supplier<McpStoredAuthoringDomain.Context> context,
            LongSupplier clock) {
        this.cubism = Objects.requireNonNull(cubism);
        this.execution = Objects.requireNonNull(execution);
        this.context = Objects.requireNonNull(context);
        this.clock = Objects.requireNonNull(clock);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        var support = McpVersionSupport.exact(
                "cubism.editor-model.parameter-structure.write",
                versions,
                List.of(
                        McpVersionSupport.OperationSupport.available(
                                "parameter_structure_batch",
                                McpOperationEffect.TRANSACTION_CONTROL,
                                false,
                                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                                versions,
                                "Own authoring transaction; each native folder/move/Combined call still requires exact host selectors, permissions and group-undo admission. No group reparent/reorder or generic invocation.")));
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpParameterStructureSchemas.definition(READ),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        execution,
                        this::read),
                McpRegisteredTool.typed(
                        McpParameterStructureSchemas.definition(PLAN),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        execution,
                        this::plan),
                McpRegisteredTool.typed(
                        McpParameterStructureSchemas.definition(APPLY),
                        McpOperationEffect.TRANSACTION_CONTROL,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        support,
                        execution,
                        this::apply),
                McpRegisteredTool.typed(
                        McpParameterStructureSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result)));
    }

    private Map<String, Object> read(Map<String, Object> args) {
        try {
            fields(args, "documentId", "modelId");
            var current = context.get();
            scope(args, current.binding());
            var state = capture(current.model());
            verify(current.binding(), state, true, Map.of());
            var snapshot = new Snapshot(session(), Map.copyOf(current.binding()), state, clock.getAsLong());
            String id = UUID.randomUUID().toString();
            synchronized (snapshots) {
                prune();
                trim(snapshots);
                snapshots.put(id, snapshot);
            }
            return envelope(Map.of(
                    "ok",
                    true,
                    "snapshotId",
                    id,
                    "state",
                    snapshot.binding,
                    "structure",
                    state.json(),
                    "identityScope",
                    "PARAMETER_GUIDS_GROUP_IDS_AND_HISTORY_BINDING",
                    "snapshotTtlSeconds",
                    300));
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    private Map<String, Object> plan(Map<String, Object> args) {
        try {
            fields(args, "documentId", "modelId", "snapshotId", "edits");
            Snapshot issued;
            synchronized (snapshots) {
                prune();
                issued = snapshots.get(text(args.get("snapshotId"), 128));
            }
            if (issued == null || !issued.session.equals(session())) throw new NoSuchElementException();
            scope(args, issued.binding);
            verify(issued.binding, issued.state, true, Map.of());
            // JSON roundtrip detaches all nested caller-owned collections from the retained plan.
            var copy = Json.parseObject(Json.stringify(args));
            var steps = simulate(issued.state, copy.get("edits"));
            var after = steps.get(steps.size() - 1).after();
            String id = UUID.randomUUID().toString();
            var output = Map.<String, Object>of(
                    "ok",
                    true,
                    "planId",
                    id,
                    "beforeHash",
                    digest(issued.state.json()),
                    "expectedAfterHash",
                    digest(after.json()),
                    "expectedStructure",
                    after.json(),
                    "effects",
                    steps.stream()
                            .map(s -> Map.of(
                                    "kind",
                                    s.edit().get("kind"),
                                    "deletedGroupIds",
                                    s.deletedGroups(),
                                    "deletedParameterIds",
                                    s.deletedParameters()))
                            .toList(),
                    "provisionalGroups",
                    createdRefs(steps).entrySet().stream()
                            .map(e -> Map.of("ref", e.getKey(), "provisionalId", e.getValue()))
                            .toList(),
                    "writeAdmission",
                    "NOT_CHECKED");
            if (bytes(args) > 64 * 1024 || bytes(output) > 240 * 1024) throw new Budget();
            var planned =
                    new Plan(issued, List.copyOf(steps.stream().map(Step::edit).toList()), after);
            synchronized (snapshots) {
                prune();
                if (clock.getAsLong() - issued.created >= TTL) throw new Stale();
                trim(plans);
                plans.put(id, planned);
            }
            return envelope(output);
        } catch (RuntimeException e) {
            return error(e);
        }
    }

    private Map<String, Object> apply(Map<String, Object> args) {
        String id = "";
        Key key = null;
        boolean reserved = false;
        var created = new LinkedHashMap<String, String>();
        String beforeHash = null, afterHash = null;
        try {
            fields(args, "documentId", "modelId", "planId", "operationId");
            id = text(args.get("operationId"), 128);
            key = new Key(session(), id);
            String fingerprint = digest(args);
            synchronized (operations) {
                var prior = operations.get(key);
                if (prior != null)
                    return envelope(
                            prior.fingerprint.equals(fingerprint)
                                    ? prior.output
                                    : output(
                                            id,
                                            "NOT_APPLIED",
                                            "OPERATION_ID_CONFLICT",
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            Map.of(),
                                            null,
                                            false));
            }
            Plan plan;
            synchronized (snapshots) {
                prune();
                plan = plans.get(text(args.get("planId"), 128));
            }
            if (plan == null || !plan.snapshot.session.equals(session())) throw new NoSuchElementException();
            var source = plan.snapshot;
            scope(args, source.binding);
            verify(source.binding, source.state, true, Map.of());
            var steps = simulate(source.state, plan.edits);
            if (!steps.get(steps.size() - 1).after().equals(plan.after)) throw new Stale();
            var service = cubism.authoringTransactions();
            beforeHash = digest(source.state.json());
            afterHash = digest(plan.after.json());
            synchronized (operations) {
                if (operations.size() >= 256)
                    return envelope(output(
                            id,
                            "NOT_APPLIED",
                            "RESULT_STORE_FULL",
                            null,
                            null,
                            beforeHash,
                            afterHash,
                            false,
                            Map.of(),
                            null,
                            false));
                operations.put(
                        key,
                        new Operation(
                                fingerprint,
                                output(
                                        id,
                                        "RUNNING",
                                        "RUNNING",
                                        null,
                                        null,
                                        beforeHash,
                                        afterHash,
                                        false,
                                        Map.of(),
                                        null,
                                        false)));
                reserved = true;
            }
            var result = service.execute(AuthoringTransactionOptions.of("MCP parameter structure"), () -> {
                if (clock.getAsLong() - source.created >= TTL) throw new Stale();
                verify(source.binding, source.state, true, Map.of());
                State previous = source.state;
                for (var step : steps) {
                    if (!previous.equals(step.after())) {
                        execute(step.edit(), created, source.binding);
                        verify(source.binding, step.after(), false, created);
                    }
                    previous = step.after();
                }
                return Boolean.TRUE;
            });
            String outcome =
                    switch (result.outcome()) {
                        case COMMITTED -> "APPLIED";
                        case NO_CHANGE -> "NO_CHANGE";
                        case ROLLED_BACK -> "ROLLED_BACK";
                        case RECOVERY_FAILED -> "OUTCOME_UNKNOWN";
                        default -> "NOT_APPLIED";
                    };
            boolean verified = false, restored = false;
            String actualHash = null;
            if (result.successful()) {
                try {
                    var actual = verify(source.binding, plan.after, false, created);
                    actualHash = digest(actual.json());
                    verified = true;
                } catch (RuntimeException e) {
                    outcome = "APPLIED_WITH_READBACK_WARNING";
                }
            } else if (result.outcome() == AuthoringTransactionOutcome.ROLLED_BACK) {
                try {
                    verify(source.binding, source.state, false, Map.of());
                    restored = true;
                } catch (RuntimeException e) {
                    outcome = "OUTCOME_UNKNOWN";
                }
            }
            var terminal = output(
                    id,
                    outcome,
                    result.outcome().name(),
                    result.receipt().map(McpWriteResults::receipt).orElse(null),
                    result.diagnosticId().orElse(null),
                    beforeHash,
                    afterHash,
                    verified,
                    created,
                    actualHash,
                    restored);
            finish(key, terminal);
            return envelope(terminal);
        } catch (RuntimeException failure) {
            var terminal = output(
                    id,
                    reserved ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    reserved ? "HOST_OUTCOME_UNKNOWN" : code(failure),
                    null,
                    null,
                    beforeHash,
                    afterHash,
                    false,
                    created,
                    null,
                    false);
            if (reserved) finish(key, terminal);
            return envelope(terminal);
        }
    }

    private void execute(Map<String, Object> edit, Map<String, String> created, Map<String, Object> binding) {
        var current = context.get();
        if (!sameScope(binding, current.binding())) throw new Stale();
        var model = current.model();
        String kind = (String) edit.get("kind");
        switch (kind) {
            case "create_group" -> {
                String id = text(
                        model.parameterGroups()
                                .addGroup((String) edit.get("name"))
                                .id()
                                .value(),
                        4096);
                if (created.containsValue(id)) throw new Stale();
                created.put((String) edit.get("ref"), id);
            }
            case "rename_group" ->
                model.parameterGroups()
                        .find(new ParameterGroupId(target(edit.get("group"), created)))
                        .rename((String) edit.get("name"));
            case "remove_group" ->
                model.parameterGroups().removeGroup(new ParameterGroupId(target(edit.get("group"), created)));
            case "move_parameter" -> {
                try {
                    model.parameterGroups()
                            .moveParameter(
                                    new ParameterId((String) edit.get("parameterId")),
                                    new ParameterGroupId(target(edit.get("targetGroup"), created)),
                                    ((Number) edit.get("insertIndex")).intValue());
                } catch (NoSuchMethodError | AbstractMethodError unavailable) {
                    throw new UnsupportedOperationException(
                            "Indexed parameter move SDK API is unavailable.", unavailable);
                }
            }
            case "combine" ->
                model.parameters()
                        .find(new ParameterId((String) edit.get("parameterId")))
                        .combineWith(new ParameterId((String) edit.get("partnerId")));
            case "uncombine" ->
                model.parameters()
                        .find(new ParameterId((String) edit.get("parameterId")))
                        .uncombine();
            default -> throw new IllegalArgumentException();
        }
    }

    private State verify(
            Map<String, Object> binding, State expected, boolean exactHistory, Map<String, String> created) {
        var current = context.get();
        if (exactHistory ? !binding.equals(current.binding()) : !sameScope(binding, current.binding()))
            throw new Stale();
        var actual = capture(current.model());
        var aliases = new HashMap<String, String>();
        created.forEach((ref, id) -> aliases.put(id, NEW + ref));
        if (!actual.normalize(aliases).equals(expected)
                || !current.binding().equals(context.get().binding())) throw new Stale();
        return actual;
    }

    private Map<String, Object> result(Map<String, Object> args) {
        fields(args, "operationId");
        String id = text(args.get("operationId"), 128);
        synchronized (operations) {
            var old = operations.get(new Key(session(), id));
            return envelope(
                    old == null
                            ? output(
                                    id,
                                    "OUTCOME_UNKNOWN",
                                    "NOT_FOUND",
                                    null,
                                    null,
                                    null,
                                    null,
                                    false,
                                    Map.of(),
                                    null,
                                    false)
                            : old.output);
        }
    }

    private void finish(Key key, Map<String, Object> output) {
        synchronized (operations) {
            var old = operations.get(key);
            operations.put(key, new Operation(old.fingerprint, output));
        }
    }

    private static Map<String, String> createdRefs(List<Step> steps) {
        var map = new LinkedHashMap<String, String>();
        for (var s : steps)
            if (s.edit().get("kind").equals("create_group")) {
                String ref = (String) s.edit().get("ref");
                map.put(ref, NEW + ref);
            }
        return map;
    }

    private static Map<String, Object> output(
            String id,
            String outcome,
            String code,
            Map<String, Object> receipt,
            String diagnostic,
            String before,
            String after,
            boolean verified,
            Map<String, String> created,
            String actual,
            boolean restored) {
        var m = new LinkedHashMap<>(out(id, outcome, code, receipt, diagnostic, before, after, verified));
        m.put(
                "createdGroups",
                created.entrySet().stream()
                        .map(e -> Map.of("ref", e.getKey(), "groupId", e.getValue()))
                        .toList());
        m.put("actualAfterHash", actual);
        m.put("rollbackStateVerified", restored);
        return Collections.unmodifiableMap(m);
    }

    private void prune() {
        long now = clock.getAsLong();
        snapshots.values().removeIf(s -> now - s.created >= TTL);
        plans.values().removeIf(p -> now - p.snapshot.created >= TTL);
    }

    private static void trim(Map<String, ?> map) {
        while (map.size() >= 16) map.remove(map.keySet().iterator().next());
    }

    private static boolean sameScope(Map<String, Object> a, Map<String, Object> b) {
        return List.of("documentId", "modelId", "historyGeneration", "historyDocumentBinding", "historyManagerBinding")
                .stream()
                .allMatch(k -> Objects.equals(a.get(k), b.get(k)));
    }

    private static void scope(Map<String, Object> args, Map<String, Object> binding) {
        if (!text(args.get("documentId"), 4096).equals(binding.get("documentId"))
                || !text(args.get("modelId"), 4096).equals(binding.get("modelId"))) throw new Stale();
    }

    private static String session() {
        return McpRequestRegistry.currentSessionId();
    }

    private static Map<String, Object> error(RuntimeException e) {
        return envelope(Map.of("ok", false, "error", code(e)));
    }

    private static String code(RuntimeException e) {
        if (e instanceof Stale || e instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (e instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (e instanceof Ambiguous) return "AMBIGUOUS_DISPLAY_ID";
        if (e instanceof InvalidState) return "INVALID_STRUCTURE";
        if (e instanceof Budget) return "BUDGET_EXCEEDED";
        if (e instanceof NoSuchElementException) return "NOT_FOUND";
        if (e instanceof UnsupportedOperationException) return "UNAVAILABLE";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof IllegalArgumentException || e instanceof NullPointerException) return "INVALID_ARGUMENT";
        return "READ_OR_PREFLIGHT_FAILED";
    }

    private record Snapshot(String session, Map<String, Object> binding, State state, long created) {}

    private record Plan(Snapshot snapshot, List<Map<String, Object>> edits, State after) {}

    private record Key(String session, String id) {}

    private record Operation(String fingerprint, Map<String, Object> output) {}

    private static final class Stale extends IllegalStateException {}
}
