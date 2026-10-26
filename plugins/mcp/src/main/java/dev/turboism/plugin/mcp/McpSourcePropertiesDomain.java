package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpWriteResults.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Explicit source-level property writes; never edits a current/selected parameter form. */
final class McpSourcePropertiesDomain {
    static final String READ = "turboism.authoring.properties.read",
            WRITE = "turboism.authoring.properties.write",
            RESULT = "turboism.authoring.properties.result";
    private final CubismFacade cubism;
    private final McpExecutionBridge execution;
    private final McpAuthoringSnapshotStore snapshots;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final Map<Key, Operation> operations = new HashMap<>();

    McpSourcePropertiesDomain(
            CubismFacade cubism,
            McpExecutionBridge execution,
            McpAuthoringSnapshotStore snapshots,
            Supplier<McpStoredAuthoringDomain.Context> context) {
        this.cubism = Objects.requireNonNull(cubism);
        this.execution = Objects.requireNonNull(execution);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.context = Objects.requireNonNull(context);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        return McpToolCatalog.of(List.of(
                McpRegisteredTool.typed(
                        McpSourcePropertiesSchemas.definition(READ),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        execution,
                        this::read),
                McpRegisteredTool.typed(
                        McpSourcePropertiesSchemas.definition(WRITE),
                        McpOperationEffect.TRANSACTION_CONTROL,
                        McpExecutionAffinity.UI_THREAD,
                        false,
                        McpVersionSupport.exact(
                                "cubism.editor-model.objects.write",
                                versions,
                                List.of(
                                        McpVersionSupport.OperationSupport.available(
                                                "source_properties_batch",
                                                McpOperationEffect.TRANSACTION_CONTROL,
                                                false,
                                                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                                                versions,
                                                "Source visibility/lock for ArtMesh/Warp/Rotation and ArtMesh culling only. Full stored-state CAS, explicit properties and own SDK transaction; exact native authoring/Undo admission remains authoritative."))),
                        execution,
                        this::write),
                McpRegisteredTool.typed(
                        McpSourcePropertiesSchemas.definition(RESULT),
                        McpOperationEffect.READ,
                        McpExecutionAffinity.DIRECT,
                        false,
                        execution,
                        this::result)));
    }

    private Map<String, Object> read(Map<String, Object> args) {
        try {
            fields(args, "documentId", "modelId", "kind", "objectId", "authoringSnapshotId");
            var current = context.get();
            scope(args, current);
            String kind = kind(args.get("kind")), id = text(args.get("objectId"), 4096);
            var expected =
                    snapshots.get(text(args.get("authoringSnapshotId"), 128), McpRequestRegistry.currentSessionId());
            if (!expected.kind().equals(kind)
                    || !expected.objectId().equals(id)
                    || !expected.binding().equals(current.binding())) throw new Stale();
            verifyNative(current, expected);
            var properties = properties(current.model(), kind, id);
            verifyNative(current, expected);
            if (!properties.equals(properties(current.model(), kind, id))
                    || !current.binding().equals(context.get().binding())) throw new Stale();
            var row = new LinkedHashMap<String, Object>(properties);
            row.put("kind", "source_properties");
            var rows = new ArrayList<Map<String, Object>>();
            rows.add(expected.rows().stream()
                    .filter(r -> "parent_space".equals(r.get("kind")))
                    .findFirst()
                    .orElseThrow(Stale::new));
            rows.add(row);
            // Native DTO is already retained by the authoring cache; properties never replace its contents.
            int budget = expected.encodedBytes() + Json.stringify(row).getBytes(StandardCharsets.UTF_8).length;
            if (budget > McpAuthoringSnapshotStore.MAX_ENTRY_BYTES) throw new Budget();
            var issued = snapshots.put(
                    McpRequestRegistry.currentSessionId(),
                    current.binding(),
                    kind + "_properties",
                    id,
                    expected.sourceGuid(),
                    expected.nativeSnapshot(),
                    rows,
                    budget,
                    expected.snapshotId());
            return envelope(Map.of(
                    "ok",
                    true,
                    "kind",
                    kind,
                    "objectId",
                    id,
                    "sourceGuid",
                    issued.sourceGuid(),
                    "snapshotId",
                    issued.snapshotId(),
                    "state",
                    issued.binding(),
                    "scope",
                    "source_object",
                    "properties",
                    properties));
        } catch (RuntimeException e) {
            return envelope(Map.of("ok", false, "error", code(e)));
        }
    }

    private Map<String, Object> write(Map<String, Object> args) {
        String id = "";
        Key key = null;
        boolean reserved = false;
        try {
            fields(args, "documentId", "modelId", "operationId", "targets");
            id = text(args.get("operationId"), 128);
            key = new Key(McpRequestRegistry.currentSessionId(), id);
            String fingerprint = digest(args);
            synchronized (operations) {
                var old = operations.get(key);
                if (old != null)
                    return envelope(
                            old.fingerprint.equals(fingerprint)
                                    ? old.output
                                    : out(id, "NOT_APPLIED", "OPERATION_ID_CONFLICT", null, null, null, null, false));
            }
            var current = context.get();
            scope(args, current);
            if (bytes(args) > 128 * 1024) throw new Budget();
            var plans = new ArrayList<Plan>();
            var seen = new HashSet<String>();
            for (Object raw : list(args.get("targets"), 8)) {
                var target = fields(raw, "kind", "objectId", "snapshotId", "properties");
                String kind = kind(target.get("kind")), objectId = text(target.get("objectId"), 4096);
                if (!seen.add((kind.equals("art_mesh") ? "mesh:" : "deformer:") + objectId))
                    throw new IllegalArgumentException();
                var snapshot = snapshots.get(text(target.get("snapshotId"), 128), key.session);
                if (!snapshot.kind().equals(kind + "_properties")
                        || !snapshot.objectId().equals(objectId)
                        || !snapshot.binding().equals(current.binding())) throw new Stale();
                var before = new LinkedHashMap<String, Object>(snapshot.rows().stream()
                        .filter(r -> "source_properties".equals(r.get("kind")))
                        .findFirst()
                        .orElseThrow(Stale::new));
                before.remove("kind");
                var patch = patch(target.get("properties"), kind);
                var after = new LinkedHashMap<>(before);
                after.putAll(patch);
                plans.add(new Plan(kind, snapshot, Map.copyOf(before), Map.copyOf(after)));
            }
            verify(current, plans, false, true);
            var transactions = cubism.authoringTransactions();
            synchronized (operations) {
                if (operations.size() >= 256)
                    return envelope(out(id, "NOT_APPLIED", "RESULT_STORE_FULL", null, null, null, null, false));
                operations.put(
                        key, new Operation(fingerprint, out(id, "RUNNING", "RUNNING", null, null, null, null, false)));
                reserved = true;
            }
            String beforeHash = digest(summary(plans, false)), afterHash = digest(summary(plans, true));
            var result = transactions.execute(AuthoringTransactionOptions.of("MCP source properties"), () -> {
                verify(current, plans, false, true);
                for (var plan : plans) apply(current.model(), plan);
                verify(current, plans, true, false);
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
            boolean verified = false;
            if (result.successful())
                try {
                    verify(current, plans, true, false);
                    verified = true;
                } catch (RuntimeException e) {
                    outcome = "APPLIED_WITH_READBACK_WARNING";
                }
            var output = out(
                    id,
                    outcome,
                    result.outcome().name(),
                    result.receipt().map(McpWriteResults::receipt).orElse(null),
                    result.diagnosticId().orElse(null),
                    beforeHash,
                    afterHash,
                    verified);
            finish(key, output);
            return envelope(output);
        } catch (RuntimeException e) {
            var output = out(
                    id,
                    reserved ? "OUTCOME_UNKNOWN" : "NOT_APPLIED",
                    reserved ? "HOST_OUTCOME_UNKNOWN" : code(e),
                    null,
                    null,
                    null,
                    null,
                    false);
            if (reserved) finish(key, output);
            return envelope(output);
        }
    }

    private Map<String, Object> result(Map<String, Object> args) {
        fields(args, "operationId");
        String id = text(args.get("operationId"), 128);
        synchronized (operations) {
            var op = operations.get(new Key(McpRequestRegistry.currentSessionId(), id));
            return envelope(
                    op == null ? out(id, "OUTCOME_UNKNOWN", "NOT_FOUND", null, null, null, null, false) : op.output);
        }
    }

    private void finish(Key key, Map<String, Object> output) {
        synchronized (operations) {
            var old = operations.get(key);
            operations.put(key, new Operation(old.fingerprint, output));
        }
    }

    private void verify(McpStoredAuthoringDomain.Context initial, List<Plan> plans, boolean after, boolean history) {
        var current = context.get();
        if (history ? !initial.binding().equals(current.binding()) : !sameScope(initial.binding(), current.binding()))
            throw new Stale();
        for (var plan : plans) {
            verifyNative(current, plan.snapshot);
            if (!(after ? plan.after : plan.before)
                    .equals(properties(current.model(), plan.kind, plan.snapshot.objectId()))) throw new Stale();
        }
        if (!current.binding().equals(context.get().binding())) throw new Stale();
    }

    private void verifyNative(McpStoredAuthoringDomain.Context current, McpAuthoringSnapshotStore.Entry expected) {
        String kind = expected.kind().replace("_properties", "");
        String id = expected.objectId();
        Object nativeState;
        String part, deformer;
        if (kind.equals("art_mesh")) {
            var handle = current.model().drawables().find(new ArtMeshId(id));
            nativeState = handle.authoringSnapshot();
            part = handle.parentPartId().map(v -> v.value()).orElse(null);
            deformer = handle.parentDeformerId().map(v -> v.value()).orElse(null);
        } else {
            var handle = current.model().deformers().find(new DeformerId(id));
            if (kind.equals("warp_deformer") && handle instanceof WarpDeformer w) nativeState = w.authoringSnapshot();
            else if (kind.equals("rotation_deformer") && handle instanceof RotationDeformer r)
                nativeState = r.authoringSnapshot();
            else throw new Stale();
            part = handle.parentPartId().map(v -> v.value()).orElse(null);
            deformer = handle.parentDeformerId().map(v -> v.value()).orElse(null);
        }
        if (!expected.nativeSnapshot().equals(nativeState)) throw new Stale();
        var parent = expected.rows().stream()
                .filter(r -> "parent_space".equals(r.get("kind")))
                .findFirst()
                .orElseThrow(Stale::new);
        if (!Objects.equals(part, parent.get("parentPartId"))
                || !Objects.equals(deformer, parent.get("parentDeformerId"))) throw new Stale();
        if (!current.binding().equals(context.get().binding())) throw new Stale();
    }

    private static Map<String, Object> properties(CubismModel model, String kind, String id) {
        if (kind.equals("art_mesh")) {
            var handle = model.drawables().find(new ArtMeshId(id));
            return Map.of("visible", handle.visible(), "locked", handle.locked(), "culling", handle.culling());
        }
        var handle = model.deformers().find(new DeformerId(id));
        if (kind.equals("warp_deformer") ? !(handle instanceof WarpDeformer) : !(handle instanceof RotationDeformer))
            throw new Stale();
        return Map.of("visible", handle.visible(), "locked", handle.locked());
    }

    private static void apply(CubismModel model, Plan plan) {
        if (plan.kind.equals("art_mesh")) {
            var handle = model.drawables().find(new ArtMeshId(plan.snapshot.objectId()));
            if (changed(plan, "visible")) handle.setVisible((Boolean) plan.after.get("visible"));
            if (changed(plan, "culling")) handle.setCulling((Boolean) plan.after.get("culling"));
            if (changed(plan, "locked")) handle.setLocked((Boolean) plan.after.get("locked"));
        } else {
            var handle = model.deformers().find(new DeformerId(plan.snapshot.objectId()));
            if (changed(plan, "visible")) handle.setVisible((Boolean) plan.after.get("visible"));
            if (changed(plan, "locked")) handle.setLocked((Boolean) plan.after.get("locked"));
        }
    }

    private static boolean changed(Plan plan, String field) {
        return !Objects.equals(plan.before.get(field), plan.after.get(field));
    }

    private static List<Map<String, Object>> summary(List<Plan> plans, boolean after) {
        return plans.stream()
                .map(p -> Map.<String, Object>of(
                        "kind",
                        p.kind,
                        "objectId",
                        p.snapshot.objectId(),
                        "sourceGuid",
                        p.snapshot.sourceGuid(),
                        "properties",
                        after ? p.after : p.before))
                .toList();
    }

    private static Map<String, Object> patch(Object raw, String kind) {
        if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) throw new IllegalArgumentException();
        var result = new LinkedHashMap<String, Object>();
        for (var e : m.entrySet()) {
            String field = (String) e.getKey();
            if (!(kind.equals("art_mesh") ? Set.of("visible", "locked", "culling") : Set.of("visible", "locked"))
                            .contains(field)
                    || !(e.getValue() instanceof Boolean)) throw new IllegalArgumentException();
            result.put(field, e.getValue());
        }
        return result;
    }

    private static void scope(Map<String, Object> args, McpStoredAuthoringDomain.Context context) {
        if (!text(args.get("documentId"), 4096).equals(context.binding().get("documentId"))
                || !text(args.get("modelId"), 4096).equals(context.binding().get("modelId"))) throw new Stale();
    }

    private static boolean sameScope(Map<String, Object> a, Map<String, Object> b) {
        return List.of("documentId", "modelId", "historyGeneration", "historyDocumentBinding", "historyManagerBinding")
                .stream()
                .allMatch(k -> Objects.equals(a.get(k), b.get(k)));
    }

    private static String kind(Object raw) {
        String k = text(raw, 32);
        if (!Set.of("art_mesh", "warp_deformer", "rotation_deformer").contains(k)) throw new IllegalArgumentException();
        return k;
    }

    private static String text(Object raw, int max) {
        if (!(raw instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        return s;
    }

    private static List<?> list(Object raw, int max) {
        if (!(raw instanceof List<?> l) || l.isEmpty() || l.size() > max) throw new IllegalArgumentException();
        return l;
    }

    private static Map<String, Object> fields(Object raw, String... keys) {
        if (!(raw instanceof Map<?, ?> m) || !m.keySet().equals(Set.of(keys))) throw new IllegalArgumentException();
        var result = new LinkedHashMap<String, Object>();
        m.forEach((k, v) -> result.put((String) k, v));
        return result;
    }

    private static int bytes(Map<String, Object> value) {
        return Json.stringify(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String code(RuntimeException e) {
        if (e instanceof Stale || e instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (e instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (e instanceof NoSuchElementException) return "NOT_FOUND";
        if (e instanceof Budget) return "BUDGET_EXCEEDED";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof UnsupportedOperationException) return "UNAVAILABLE";
        if (e instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        return "READ_OR_PREFLIGHT_FAILED";
    }

    private record Plan(
            String kind,
            McpAuthoringSnapshotStore.Entry snapshot,
            Map<String, Object> before,
            Map<String, Object> after) {}

    private record Key(String session, String id) {}

    private record Operation(String fingerprint, Map<String, Object> output) {}

    private static final class Stale extends IllegalStateException {}

    private static final class Budget extends IllegalArgumentException {}
}
