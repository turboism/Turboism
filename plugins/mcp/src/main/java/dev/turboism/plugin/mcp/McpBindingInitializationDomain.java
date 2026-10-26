package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpWriteResults.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Plans and verifies an ordinary grid on one previously unbound source, in one owned transaction. */
final class McpBindingInitializationDomain {
    static final String PLAN = "turboism.authoring.bindings.initialize_plan",
            APPLY = "turboism.authoring.bindings.initialize",
            RESULT = "turboism.authoring.bindings.initialize_result";
    static final int MAX_PLANS = 16, MAX_OPERATIONS = 128, MAX_BYTES = 4 * 1024 * 1024;
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final McpAuthoringSnapshotStore snapshots;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Plan> plans = new LinkedHashMap<>();
    private final Map<Key, Operation> operations = new HashMap<>();

    McpBindingInitializationDomain(
            CubismFacade cubism,
            McpExecutionBridge execution,
            McpAuthoringSnapshotStore snapshots,
            Supplier<McpStoredAuthoringDomain.Context> context) {
        this(cubism, execution, snapshots, context, System::nanoTime);
    }

    McpBindingInitializationDomain(
            CubismFacade cubism,
            McpExecutionBridge execution,
            McpAuthoringSnapshotStore snapshots,
            Supplier<McpStoredAuthoringDomain.Context> context,
            LongSupplier clock) {
        this.cubism = Objects.requireNonNull(cubism);
        this.execution = Objects.requireNonNull(execution);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.context = Objects.requireNonNull(context);
        this.clock = Objects.requireNonNull(clock);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        var result = new ArrayList<McpRegisteredTool>();
        for (String name : List.of(PLAN, APPLY, RESULT)) {
            boolean write = name.equals(APPLY);
            result.add(McpRegisteredTool.typed(
                    McpBindingInitializationSchemas.definition(name),
                    write ? McpOperationEffect.TRANSACTION_CONTROL : McpOperationEffect.READ,
                    name.equals(RESULT) ? McpExecutionAffinity.DIRECT : McpExecutionAffinity.UI_THREAD,
                    false,
                    McpVersionSupport.exact(
                            "cubism.editor-model.parameter-bindings",
                            versions,
                            List.of(
                                    McpVersionSupport.OperationSupport.available(
                                            write ? "initialize_ordinary_grid" : "observe_grid_plan_or_result",
                                            write ? McpOperationEffect.TRANSACTION_CONTROL : McpOperationEffect.READ,
                                            false,
                                            write
                                                    ? McpVersionSupport.UndoVerification.RUNTIME_VERIFIED
                                                    : McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                            versions,
                                            "Full source/parameter GUID/metadata/history CAS; Runtime exact binding and transaction admission is authoritative. Complete independent stored grid/default content verified, not visual or real-host acceptance."))),
                    execution,
                    args -> name.equals(PLAN) ? plan(args) : name.equals(APPLY) ? apply(args) : result(args)));
        }
        return McpToolCatalog.of(result);
    }

    private Map<String, Object> plan(Map<String, Object> args) {
        try {
            fields(args, "documentId", "modelId", "objectId", "kind", "authoringSnapshotId", "axes");
            var current = context.get();
            scope(args, current);
            String session = McpRequestRegistry.currentSessionId();
            var expected = snapshots.get(text(args.get("authoringSnapshotId"), 128), session);
            if (!expected.kind().equals(text(args.get("kind"), 64))
                    || !expected.objectId().equals(text(args.get("objectId"), 4096))
                    || !expected.binding().equals(current.binding())) throw new Stale();
            var original = expected.nativeSnapshot();
            McpBindingGridSnapshot.initial(original);
            var metadata = metadata(current.model(), expected);
            if (Boolean.TRUE.equals(metadata.get("locked"))) throw new UnsupportedOperationException();
            var parameters = parameters(current.model());
            var axes = axes(args.get("axes"), parameters);
            int count = 1;
            for (var axis : axes) count = Math.multiplyExact(count, axis.keys().size());
            if (count > McpBindingGridSnapshot.MAX_CELLS
                    || (long) count * McpBindingGridSnapshot.view(original).points()
                            > McpBindingGridSnapshot.MAX_SAMPLES) throw new McpBindingGridSnapshot.Budget();
            var prepared = new Plan(
                    UUID.randomUUID().toString(),
                    session,
                    expected,
                    parameters,
                    metadata,
                    axes,
                    clock.getAsLong(),
                    bytes(Map.of(
                            "source",
                            McpBindingGridSnapshot.projection(original),
                            "metadata",
                            metadata,
                            "parameters",
                            parameters)));
            if (prepared.bytes > MAX_BYTES) throw new McpBindingGridSnapshot.Budget();
            verify(prepared, original, true);
            synchronized (plans) {
                prune();
                while (plans.size() >= MAX_PLANS
                        || plans.values().stream().mapToLong(p -> p.bytes).sum() + prepared.bytes > 16L * 1024 * 1024)
                    plans.remove(plans.keySet().iterator().next());
                plans.put(prepared.id, prepared);
            }
            return envelope(Map.of(
                    "ok",
                    true,
                    "planId",
                    prepared.id,
                    "objectId",
                    expected.objectId(),
                    "sourceGuid",
                    expected.sourceGuid(),
                    "axes",
                    axes.stream().map(McpBindingGridSnapshot.Axis::wire).toList(),
                    "cellCount",
                    count,
                    "storedSamples",
                    count * McpBindingGridSnapshot.view(original).points(),
                    "state",
                    expected.binding(),
                    "hostWriteAdmission",
                    "UNPROVEN",
                    "target",
                    approvalTarget(prepared)));
        } catch (RuntimeException failure) {
            return envelope(Map.of("ok", false, "error", code(failure)));
        }
    }

    private Map<String, Object> apply(Map<String, Object> args) {
        String id = "";
        Key key = null;
        boolean reserved = false;
        String beforeHash = null;
        try {
            fields(args, "documentId", "modelId", "planId", "operationId", "target");
            id = text(args.get("operationId"), 128);
            key = new Key(McpRequestRegistry.currentSessionId(), id);
            String fingerprint = digest(args);
            synchronized (operations) {
                var old = operations.get(key);
                if (old != null)
                    return envelope(
                            old.fingerprint.equals(fingerprint)
                                    ? old.output
                                    : output(
                                            id,
                                            "NOT_APPLIED",
                                            "OPERATION_ID_CONFLICT",
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            List.of()));
            }
            var current = context.get();
            scope(args, current);
            Plan prepared;
            synchronized (plans) {
                prune();
                prepared = plans.get(text(args.get("planId"), 128));
                if (prepared == null || !prepared.session.equals(key.session)) throw new NoSuchElementException();
            }
            if (!prepared.expected.binding().equals(current.binding())) throw new Stale();
            if (!digest(approvalTarget(prepared)).equals(digest(args.get("target")))) throw new Stale();
            snapshots.get(prepared.expected.snapshotId(), key.session);
            verify(prepared, prepared.expected.nativeSnapshot(), true);
            beforeHash = hash(prepared, prepared.expected.nativeSnapshot());
            var transactions = cubism.authoringTransactions();
            synchronized (operations) {
                if (operations.size() >= MAX_OPERATIONS)
                    return envelope(output(
                            id, "NOT_APPLIED", "RESULT_STORE_FULL", null, null, beforeHash, null, false, List.of()));
                operations.put(
                        key,
                        new Operation(
                                fingerprint,
                                output(id, "RUNNING", "RUNNING", null, null, beforeHash, null, false, List.of())));
                reserved = true;
            }
            var tx =
                    transactions.execute(AuthoringTransactionOptions.of("MCP initialize ordinary binding grid"), () -> {
                        snapshots.get(prepared.expected.snapshotId(), prepared.session);
                        verify(prepared, prepared.expected.nativeSnapshot(), true);
                        var target = target(prepared.expected);
                        var applied = new ArrayList<McpBindingGridSnapshot.Axis>();
                        Object after = prepared.expected.nativeSnapshot();
                        for (var axis : prepared.axes) {
                            // Read every parameter definition/value and complete previous target state before each
                            // mutation.
                            verify(prepared, after, applied.isEmpty());
                            current.model()
                                    .parameterBindings(new ParameterId(axis.id()))
                                    .bindValues(target, axis.keys());
                            applied.add(axis);
                            after = nativeSnapshot(current.model(), prepared.expected);
                            budget(after);
                            McpBindingGridSnapshot.verify(prepared.expected.nativeSnapshot(), after, applied);
                            verify(prepared, after, false);
                        }
                        return after;
                    });
            String outcome =
                    switch (tx.outcome()) {
                        case COMMITTED -> "APPLIED";
                        case NO_CHANGE -> "NO_CHANGE";
                        case ROLLED_BACK -> "ROLLED_BACK";
                        case RECOVERY_FAILED -> "OUTCOME_UNKNOWN";
                        default -> "NOT_APPLIED";
                    };
            String resultCode = tx.outcome().name();
            String afterHash = null;
            boolean verified = false;
            List<Map<String, Object>> cells = List.of();
            if (tx.successful()) {
                try {
                    Object after = tx.value().orElseThrow(Stale::new);
                    cells = McpBindingGridSnapshot.verify(prepared.expected.nativeSnapshot(), after, prepared.axes);
                    verify(prepared, after, false);
                    afterHash = hash(prepared, after);
                    verified = true;
                } catch (RuntimeException failure) {
                    outcome = "APPLIED_WITH_READBACK_WARNING";
                    cells = List.of();
                }
            } else if (tx.outcome() == AuthoringTransactionOutcome.ROLLED_BACK) {
                try {
                    verify(prepared, prepared.expected.nativeSnapshot(), false);
                } catch (RuntimeException failure) {
                    outcome = "OUTCOME_UNKNOWN";
                    resultCode = "ROLLBACK_READBACK_FAILED";
                }
            }
            var output = output(
                    id,
                    outcome,
                    resultCode,
                    tx.receipt().map(McpWriteResults::receipt).orElse(null),
                    tx.diagnosticId().orElse(null),
                    beforeHash,
                    afterHash,
                    verified,
                    cells);
            finish(key, output);
            if (tx.successful()) snapshots.invalidate(prepared.expected.snapshotId());
            return envelope(output);
        } catch (RuntimeException failure) {
            var output = output(
                    id,
                    reserved ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    reserved ? "HOST_OUTCOME_UNKNOWN" : code(failure),
                    null,
                    null,
                    beforeHash,
                    null,
                    false,
                    List.of());
            if (reserved) finish(key, output);
            return envelope(output);
        }
    }

    private void verify(Plan plan, Object expectedNative, boolean exactHistory) {
        var now = context.get();
        if (exactHistory
                ? !plan.expected.binding().equals(now.binding())
                : !sameScope(plan.expected.binding(), now.binding())) throw new Stale();
        if (!expectedNative.equals(nativeSnapshot(now.model(), plan.expected))
                || !plan.metadata.equals(metadata(now.model(), plan.expected))
                || !plan.parameters.equals(parameters(now.model()))
                || !expectedNative.equals(nativeSnapshot(now.model(), plan.expected))
                || !now.binding().equals(context.get().binding())) throw new Stale();
    }

    private static Object nativeSnapshot(CubismModel model, McpAuthoringSnapshotStore.Entry expected) {
        Object value;
        boolean identity;
        if (expected.kind().equals("art_mesh")) {
            var snapshot =
                    model.drawables().find(new ArtMeshId(expected.objectId())).authoringSnapshot();
            value = snapshot;
            identity = snapshot.artMeshId().value().equals(expected.objectId())
                    && snapshot.sourceGuid().equals(expected.sourceGuid());
        } else {
            var deformer = model.deformers().find(new DeformerId(expected.objectId()));
            if (expected.kind().equals("warp_deformer") && deformer instanceof WarpDeformer warp) {
                var snapshot = warp.authoringSnapshot();
                value = snapshot;
                identity = snapshot.deformerId().value().equals(expected.objectId())
                        && snapshot.sourceGuid().equals(expected.sourceGuid());
            } else if (expected.kind().equals("rotation_deformer") && deformer instanceof RotationDeformer rotation) {
                var snapshot = rotation.authoringSnapshot();
                value = snapshot;
                identity = snapshot.deformerId().value().equals(expected.objectId())
                        && snapshot.sourceGuid().equals(expected.sourceGuid());
            } else throw new UnsupportedOperationException();
        }
        if (!identity) throw new Stale();
        return value;
    }

    private static Map<String, Object> metadata(CubismModel model, McpAuthoringSnapshotStore.Entry expected) {
        Optional<PartId> part;
        Optional<DeformerId> parent;
        boolean locked;
        if (expected.kind().equals("art_mesh")) {
            var mesh = model.drawables().find(new ArtMeshId(expected.objectId()));
            part = mesh.parentPartId();
            parent = mesh.parentDeformerId();
            locked = mesh.locked();
        } else {
            Deformer deformer = model.deformers().find(new DeformerId(expected.objectId()));
            part = deformer.parentPartId();
            parent = deformer.parentDeformerId();
            locked = deformer.locked();
        }
        var m = new LinkedHashMap<String, Object>();
        m.put("parentPartId", part.map(PartId::value).orElse(null));
        m.put("parentDeformerId", parent.map(DeformerId::value).orElse(null));
        m.put("locked", locked);
        m.put("defaultKeyformLocked", model.defaultKeyformLocked());
        m.put("editLevel", model.editLevel().name());
        var issuedParent = expected.rows().stream()
                .filter(r -> "parent_space".equals(r.get("kind")))
                .findFirst()
                .orElseThrow(Stale::new);
        if (!Objects.equals(issuedParent.get("parentPartId"), m.get("parentPartId"))
                || !Objects.equals(issuedParent.get("parentDeformerId"), m.get("parentDeformerId"))) throw new Stale();
        return Collections.unmodifiableMap(m);
    }

    private static List<Map<String, Object>> parameters(CubismModel model) {
        var all = model.parameters().all();
        if (all.size() > 512) throw new McpBindingGridSnapshot.Budget();
        var ids = new HashSet<String>();
        var guids = new HashSet<String>();
        var rows = new ArrayList<Map<String, Object>>();
        for (var p : all) {
            String id = text(p.id().value(), 4096), guid = text(p.guid(), 4096);
            if (!ids.add(id) || !guids.add(guid)) throw new UnsupportedOperationException();
            float min = finite(p.getMinimumValue()),
                    max = finite(p.getMaximumValue()),
                    def = finite(p.getDefaultValue());
            if (min > def || def > max) throw new IllegalArgumentException();
            var row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("guid", guid);
            row.put("minimum", min);
            row.put("defaultValue", def);
            row.put("maximum", max);
            row.put("value", finite(p.getValue()));
            row.put("type", p.type().name());
            row.put("name", p.name().orElse(null));
            row.put("repeat", p.repeat().orElse(null));
            row.put("combined", p.combined().orElse(null));
            row.put("combinedWith", p.combinedWith().map(ParameterId::value).orElse(null));
            if (row.get("name") instanceof String name && name.length() > 4096)
                throw new McpBindingGridSnapshot.Budget();
            rows.add(Collections.unmodifiableMap(row));
        }
        if (bytes(Map.of("parameters", rows)) > 240 * 1024) throw new McpBindingGridSnapshot.Budget();
        return List.copyOf(rows);
    }

    private static List<McpBindingGridSnapshot.Axis> axes(Object value, List<Map<String, Object>> parameters) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 3) throw new IllegalArgumentException();
        var result = new ArrayList<McpBindingGridSnapshot.Axis>();
        var seen = new HashSet<String>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) throw new IllegalArgumentException();
            @SuppressWarnings("unchecked")
            var row = (Map<String, Object>) raw;
            fields(row, "parameterId", "parameterGuid", "keys");
            String id = text(row.get("parameterId"), 4096), guid = text(row.get("parameterGuid"), 128);
            var p = parameters.stream()
                    .filter(r -> id.equals(r.get("id")) && guid.equals(r.get("guid")))
                    .findFirst()
                    .orElseThrow(Stale::new);
            if (!seen.add(guid)) throw new IllegalArgumentException();
            if (!"NORMAL".equals(p.get("type"))
                    || !Boolean.FALSE.equals(p.get("repeat"))
                    || !Boolean.FALSE.equals(p.get("combined"))
                    || p.get("combinedWith") != null) throw new UnsupportedOperationException();
            if (!Objects.equals(p.get("value"), p.get("defaultValue"))) throw new UnsupportedOperationException();
            float min = (Float) p.get("minimum"), max = (Float) p.get("maximum"), def = (Float) p.get("defaultValue");
            if (!(row.get("keys") instanceof List<?> keys) || keys.size() < 3 || keys.size() > 9)
                throw new IllegalArgumentException();
            var parsed = keys.stream()
                    .map(McpBindingInitializationDomain::number)
                    .sorted()
                    .toList();
            if (!parsed.contains(def)
                    || parsed.get(0) >= def
                    || parsed.get(parsed.size() - 1) <= def
                    || parsed.get(0) < min
                    || parsed.get(parsed.size() - 1) > max) throw new IllegalArgumentException();
            for (int i = 1; i < parsed.size(); i++)
                if (parsed.get(i) - parsed.get(i - 1) <= .000001f) throw new IllegalArgumentException();
            result.add(new McpBindingGridSnapshot.Axis(guid, id, min, def, max, parsed));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> approvalTarget(Plan plan) {
        return Map.of(
                "objectId",
                plan.expected.objectId(),
                "kind",
                plan.expected.kind(),
                "sourceGuid",
                plan.expected.sourceGuid(),
                "axes",
                plan.axes.stream().map(McpBindingGridSnapshot.Axis::wire).toList());
    }

    private static ParameterBindingTarget target(McpAuthoringSnapshotStore.Entry entry) {
        return switch (entry.kind()) {
            case "art_mesh" -> ParameterBindingTarget.artMesh(new ArtMeshId(entry.objectId()));
            case "warp_deformer" -> ParameterBindingTarget.warpDeformer(new DeformerId(entry.objectId()));
            case "rotation_deformer" -> ParameterBindingTarget.rotationDeformer(new DeformerId(entry.objectId()));
            default -> throw new UnsupportedOperationException();
        };
    }

    private Map<String, Object> result(Map<String, Object> args) {
        fields(args, "operationId");
        String id = text(args.get("operationId"), 128);
        synchronized (operations) {
            var op = operations.get(new Key(McpRequestRegistry.currentSessionId(), id));
            return envelope(
                    op == null
                            ? output(id, "OUTCOME_UNKNOWN", "NOT_FOUND", null, null, null, null, false, List.of())
                            : op.output);
        }
    }

    private void finish(Key key, Map<String, Object> value) {
        synchronized (operations) {
            var old = operations.get(key);
            operations.put(key, new Operation(old.fingerprint, value));
        }
    }

    private void prune() {
        plans.values().removeIf(p -> clock.getAsLong() - p.created >= McpAuthoringSnapshotStore.TTL_NANOS);
    }

    private static boolean sameScope(Map<String, Object> a, Map<String, Object> b) {
        return List.of("documentId", "modelId", "historyGeneration", "historyDocumentBinding", "historyManagerBinding")
                .stream()
                .allMatch(k -> Objects.equals(a.get(k), b.get(k)));
    }

    private static void scope(Map<String, Object> args, McpStoredAuthoringDomain.Context current) {
        if (!text(args.get("documentId"), 4096).equals(current.binding().get("documentId"))
                || !text(args.get("modelId"), 4096).equals(current.binding().get("modelId"))) throw new Stale();
    }

    private static String hash(Plan plan, Object nativeState) {
        return digest(Map.of(
                "documentId",
                plan.expected.binding().get("documentId"),
                "modelId",
                plan.expected.binding().get("modelId"),
                "kind",
                plan.expected.kind(),
                "objectId",
                plan.expected.objectId(),
                "sourceGuid",
                plan.expected.sourceGuid(),
                "rows",
                McpBindingGridSnapshot.projection(nativeState)));
    }

    private static void budget(Object nativeState) {
        McpBindingGridSnapshot.view(nativeState);
        if (bytes(Map.of("source", McpBindingGridSnapshot.projection(nativeState))) > MAX_BYTES)
            throw new McpBindingGridSnapshot.Budget();
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
            List<Map<String, Object>> cells) {
        var m = new LinkedHashMap<>(out(id, outcome, code, receipt, diagnostic, before, after, verified));
        m.put("cells", cells);
        m.put("renderVerified", false);
        m.put("rollbackStateVerified", "ROLLED_BACK".equals(outcome));
        return Collections.unmodifiableMap(m);
    }

    private static String code(RuntimeException e) {
        if (e instanceof Stale || e instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (e instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (e instanceof NoSuchElementException) return "NOT_FOUND";
        if (e instanceof McpBindingGridSnapshot.Budget || e instanceof McpAuthoringProjection.BudgetExceeded)
            return "BUDGET_EXCEEDED";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof UnsupportedOperationException
                || e instanceof dev.turboism.sdk.cubism.CubismEditorApiUnavailableException) return "UNAVAILABLE";
        if (e instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        return "READ_FAILED";
    }

    private static float number(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException();
        return finite(n.floatValue());
    }

    private static float finite(float value) {
        return McpBindingGridSnapshot.zero(value);
    }

    private static int bytes(Map<String, Object> value) {
        return Json.stringify(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return s;
    }

    private static void fields(Map<String, Object> args, String... names) {
        if (!args.keySet().equals(Set.of(names))) throw new IllegalArgumentException();
    }

    private record Plan(
            String id,
            String session,
            McpAuthoringSnapshotStore.Entry expected,
            List<Map<String, Object>> parameters,
            Map<String, Object> metadata,
            List<McpBindingGridSnapshot.Axis> axes,
            long created,
            int bytes) {}

    private record Key(String session, String id) {}

    private record Operation(String fingerprint, Map<String, Object> output) {}

    private static final class Stale extends RuntimeException {}
}
