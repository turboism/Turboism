package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Read-only rig building block; submitting a preview never authorizes or performs a write. */
final class McpRotationRigDomain {
    static final String PLAN = "turboism.rig.rotation.plan";
    private final McpAuthoringSnapshotStore snapshots;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;

    McpRotationRigDomain(
            McpAuthoringSnapshotStore snapshots,
            McpExecutionBridge execution,
            Supplier<McpStoredAuthoringDomain.Context> context) {
        this.snapshots = Objects.requireNonNull(snapshots);
        this.execution = Objects.requireNonNull(execution);
        this.context = Objects.requireNonNull(context);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        return McpToolCatalog.of(List.of(McpRegisteredTool.typed(
                McpRotationRigSchemas.definition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.UI_THREAD,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-model.objects.read",
                        versions,
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "single_axis_rotation_plan",
                                        McpOperationEffect.READ,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        versions,
                                        "Read-only legacy single-axis native-angle replacement preview; no semantic inference or visual acceptance."),
                                McpVersionSupport.OperationSupport.available(
                                        "multi_axis_rotation_plan",
                                        McpOperationEffect.READ,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        versions,
                                        "Read-only additive angle plan for explicit one to three ordinary axis GUIDs, complete independent grids only; no rotation composition or visual acceptance."))),
                execution,
                this::plan)));
    }

    private Map<String, Object> plan(Map<String, Object> args) {
        try {
            var common = Set.of("documentId", "modelId", "objectId", "snapshotId", "operationId", "policy");
            var expected = new HashSet<>(common);
            boolean multi = args.containsKey("axes");
            if (multi) expected.add("axes");
            else expected.addAll(Set.of("parameterGuid", "nativeAnglePerParameterUnit"));
            if (!args.keySet().equals(expected)) throw new IllegalArgumentException();
            for (String field : List.of("documentId", "modelId", "objectId", "snapshotId", "operationId"))
                text(args.get(field));
            if (!"REPLACE_ANGLE_FROM_DEFAULT".equals(args.get("policy"))) throw new IllegalArgumentException();
            var axes = new ArrayList<McpRotationRigPlan.Axis>();
            if (multi) {
                if (!(args.get("axes") instanceof List<?> values) || values.isEmpty() || values.size() > 3)
                    throw new IllegalArgumentException();
                for (var value : values) {
                    if (!(value instanceof Map<?, ?> axis)
                            || !axis.keySet().equals(Set.of("parameterGuid", "nativeAnglePerParameterUnit")))
                        throw new IllegalArgumentException();
                    axes.add(axis(axis));
                }
            } else axes.add(axis(args));
            var initial = context.get();
            if (!args.get("documentId").equals(initial.binding().get("documentId"))
                    || !args.get("modelId").equals(initial.binding().get("modelId"))) throw new Stale();
            var snapshot = snapshots.get(text(args.get("snapshotId")), McpRequestRegistry.currentSessionId());
            if (!"rotation_deformer".equals(snapshot.kind())
                    || !(snapshot.nativeSnapshot() instanceof RotationAuthoringSnapshot rotation))
                throw new UnsupportedOperationException();
            if (!snapshot.binding().equals(initial.binding())
                    || !snapshot.objectId().equals(args.get("objectId"))
                    || !rotation.deformerId().value().equals(snapshot.objectId())
                    || !rotation.sourceGuid().equals(snapshot.sourceGuid())) throw new Stale();
            verify(snapshot, initial);
            var edits = McpRotationRigPlan.edits(rotation, axes);
            var request = Map.of(
                    "documentId",
                    args.get("documentId"),
                    "modelId",
                    args.get("modelId"),
                    "operationId",
                    args.get("operationId"),
                    "targets",
                    List.of(Map.of(
                            "kind",
                            "rotation_deformer",
                            "objectId",
                            snapshot.objectId(),
                            "snapshotId",
                            snapshot.snapshotId(),
                            "edits",
                            edits)));
            var output = Map.<String, Object>of(
                    "ok",
                    true,
                    "applied",
                    false,
                    "policy",
                    args.get("policy"),
                    "writeTool",
                    McpKeyformWriteDomain.WRITE,
                    "writeRequest",
                    request,
                    "limitations",
                    List.of(
                            "NATIVE_ANGLE_UNITS_NO_CONVERSION",
                            "REPLACES_EXISTING_TARGET_ANGLES",
                            "NO_SEMANTIC_OR_PIVOT_INFERENCE",
                            "ADDITIVE_STORED_ANGLES_NOT_ROTATION_COMPOSITION",
                            "NO_XY_EYE_MOUTH_OR_VISUAL_ACCEPTANCE",
                            "WRITE_REQUIRES_FRESH_CAS_AND_SEPARATE_AUTHORIZATION"));
            if (Json.stringify(output).getBytes(StandardCharsets.UTF_8).length > 128 * 1024)
                throw new IllegalArgumentException();
            verify(snapshot, initial);
            return envelope(output);
        } catch (RuntimeException failure) {
            String code = failure instanceof Stale || failure instanceof McpStoredAuthoringDomain.Stale
                    ? "STALE_STATE"
                    : failure instanceof McpStoredAuthoringDomain.NoModel
                            ? "NO_ACTIVE_MODEL"
                            : failure instanceof NoSuchElementException
                                    ? "NOT_FOUND"
                                    : failure instanceof SecurityException
                                                    || failure
                                                            instanceof
                                                            dev.turboism.sdk.permission.CubismPermissionException
                                            ? "PERMISSION_DENIED"
                                            : failure instanceof UnsupportedOperationException
                                                    ? "UNAVAILABLE"
                                                    : failure instanceof IllegalArgumentException
                                                            ? "INVALID_ARGUMENT"
                                                            : "READ_FAILED";
            return envelope(
                    Map.of(
                            "ok",
                            false,
                            "error",
                            Map.of(
                                    "code",
                                    code,
                                    "message",
                                    "Rotation plan rejected; rediscover identity, supported scope and snapshots. No write performed")));
        }
    }

    private void verify(McpAuthoringSnapshotStore.Entry snapshot, McpStoredAuthoringDomain.Context initial) {
        var now = context.get();
        if (!initial.binding().equals(now.binding())) throw new Stale();
        var object = now.model().deformers().find(new DeformerId(snapshot.objectId()));
        if (!(object instanceof RotationDeformer rotation)
                || !snapshot.nativeSnapshot().equals(rotation.authoringSnapshot())) throw new Stale();
        var parent = snapshot.rows().stream()
                .filter(r -> "parent_space".equals(r.get("kind")))
                .findFirst()
                .orElseThrow(Stale::new);
        if (!Objects.equals(
                        parent.get("parentPartId"),
                        rotation.parentPartId().map(v -> v.value()).orElse(null))
                || !Objects.equals(
                        parent.get("parentDeformerId"),
                        rotation.parentDeformerId().map(v -> v.value()).orElse(null))
                || !initial.binding().equals(context.get().binding())) throw new Stale();
    }

    private static McpRotationRigPlan.Axis axis(Map<?, ?> args) {
        if (!(args.get("nativeAnglePerParameterUnit") instanceof Number number)
                || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException();
        return new McpRotationRigPlan.Axis(text(args.get("parameterGuid")), number.doubleValue());
    }

    private static String text(Object raw) {
        if (!(raw instanceof String s) || s.isBlank() || s.length() > 4096) throw new IllegalArgumentException();
        return s;
    }

    private static Map<String, Object> envelope(Map<String, Object> output) {
        var safe = McpJsonSupport.encodable(output);
        return Map.of(
                "content",
                List.of(Map.of("type", "text", "text", Json.stringify(safe))),
                "structuredContent",
                safe,
                "isError",
                !Boolean.TRUE.equals(output.get("ok")));
    }

    private static final class Stale extends RuntimeException {}
}
