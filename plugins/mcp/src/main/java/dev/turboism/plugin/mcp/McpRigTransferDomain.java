package dev.turboism.plugin.mcp;

import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Generates a target-only typed write request from two live-verified stored snapshots. */
final class McpRigTransferDomain {
    static final String PLAN = "turboism.rig.transfer_geometry.plan";
    private final McpAuthoringSnapshotStore snapshots;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;

    McpRigTransferDomain(
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
                McpRigTransferSchemas.definition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.UI_THREAD,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-model.objects.read",
                        versions,
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "explicit_stored_geometry_transfer_plan",
                                        McpOperationEffect.READ,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        versions,
                                        "Explicit stored key/point correspondence plan; native write/Undo admission remains separate, no complete rig-copy or visual acceptance claim."))),
                execution,
                this::plan)));
    }

    private Map<String, Object> plan(Map<String, Object> args) {
        try {
            if (bytes(args) > 768 * 1024) throw new McpRigTransferPlan.Budget();
            for (String field : List.of("documentId", "modelId", "operationId"))
                McpKeyformContinuity.identity((String) args.get(field));
            var initial = context.get();
            if (!Objects.equals(args.get("documentId"), initial.binding().get("documentId"))
                    || !Objects.equals(args.get("modelId"), initial.binding().get("modelId")))
                throw new McpStoredSnapshotVerification.Stale();
            var source = entry(args.get("source"), args.get("kind"), initial);
            var target = entry(args.get("target"), args.get("kind"), initial);
            if (source.objectId().equals(target.objectId())
                    || source.sourceGuid().equals(target.sourceGuid())) throw new IllegalArgumentException();
            var axes = new ArrayList<McpRigTransferPlan.Axis>();
            for (Object raw : (List<?>) args.get("axes")) {
                var axis = (Map<?, ?>) raw;
                var keys = new ArrayList<McpRigTransferPlan.Key>();
                for (Object value : (List<?>) axis.get("keys")) {
                    var key = (Map<?, ?>) value;
                    keys.add(new McpRigTransferPlan.Key(key(key.get("sourceValue")), key(key.get("targetValue"))));
                }
                axes.add(new McpRigTransferPlan.Axis(
                        (String) axis.get("sourceParameterGuid"), (String) axis.get("targetParameterGuid"), keys));
            }
            var matrix = (Map<?, ?>) args.get("deltaTransform");
            var transform = new McpRigTransferPlan.Transform(
                    number(matrix.get("xx")),
                    number(matrix.get("xy")),
                    number(matrix.get("yx")),
                    number(matrix.get("yy")));
            var pointMap = new ArrayList<Integer>();
            if (args.containsKey("sourcePointIndices"))
                for (Object p : (List<?>) args.get("sourcePointIndices"))
                    pointMap.add(new java.math.BigDecimal(p.toString()).intValueExact());
            Double angleGain = args.containsKey("angleGain") ? number(args.get("angleGain")) : null;
            verify(source, target, initial);
            var plan = McpRigTransferPlan.plan(
                    source.nativeSnapshot(), target.nativeSnapshot(), axes, transform, pointMap, angleGain);
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
                            target.kind(),
                            "objectId",
                            target.objectId(),
                            "snapshotId",
                            target.snapshotId(),
                            "edits",
                            plan.edits())));
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", true);
            output.put("applied", false);
            output.put("state", initial.binding());
            output.put("writeTool", McpKeyformWriteDomain.WRITE);
            output.put("writeRequest", request);
            output.put("operationIdReserved", false);
            output.put("writeAdmission", "NOT_CHECKED");
            output.put("sourceReadScope", "VERIFIED_AT_PLAN_ONLY");
            output.put("source", reference(source, plan.sourceDefaultFormGuid()));
            output.put("target", reference(target, plan.targetDefaultFormGuid()));
            output.put(
                    "statistics",
                    Map.of(
                            "sourceCells",
                            plan.sourceCells(),
                            "targetCells",
                            plan.targetCells(),
                            "plannedForms",
                            plan.edits().size(),
                            "plannedPoints",
                            plan.plannedPoints()));
            output.put(
                    "limitations",
                    List.of(
                            "EXPLICIT_MAPPING_NOT_ANATOMICAL_INFERENCE",
                            "REPLACES_TARGET_NONDEFAULT_GEOMETRY",
                            "TARGET_DEFAULT_SOURCE_TOPOLOGY_BINDINGS_PRESERVED",
                            "NO_DRAW_ORDER_OPACITY_OR_BINDING_TRANSFER",
                            "ROTATION_COMPONENT_DELTAS_NOT_COMPOSITION",
                            "SOURCE_VERIFIED_AT_PLAN_NOT_GUARDED_BY_TARGET_WRITE",
                            "NO_PARENT_OR_COORDINATE_SPACE_CONVERSION",
                            "NO_GEOMETRY_OR_VISUAL_ACCEPTANCE",
                            "SEPARATE_PREVIEW_WRITE_CAS_AND_AUTHORIZATION_REQUIRED"));
            if (bytes(output) > 240 * 1024) throw new McpRigTransferPlan.Budget();
            verify(source, target, initial);
            return envelope(output);
        } catch (RuntimeException failure) {
            String code = failure instanceof McpStoredSnapshotVerification.Stale
                            || failure instanceof McpStoredAuthoringDomain.Stale
                    ? "STALE_STATE"
                    : failure instanceof McpStoredAuthoringDomain.NoModel
                            ? "NO_ACTIVE_MODEL"
                            : failure instanceof NoSuchElementException
                                    ? "NOT_FOUND"
                                    : failure instanceof McpRigTransferPlan.Budget
                                                    || failure instanceof McpBindingGridSnapshot.Budget
                                            ? "BUDGET_EXCEEDED"
                                            : failure instanceof SecurityException
                                                            || failure
                                                                    instanceof
                                                                    dev.turboism.sdk.permission
                                                                            .CubismPermissionException
                                                    ? "PERMISSION_DENIED"
                                                    : failure instanceof UnsupportedOperationException
                                                            ? "UNAVAILABLE"
                                                            : failure instanceof IllegalArgumentException
                                                                            || failure instanceof ArithmeticException
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
                                    "Geometry transfer plan rejected. Rediscover both stored snapshots and explicit mappings; no write performed.")));
        }
    }

    private McpAuthoringSnapshotStore.Entry entry(Object ref, Object kind, McpStoredAuthoringDomain.Context initial) {
        var fields = (Map<?, ?>) ref;
        var entry = snapshots.get((String) fields.get("snapshotId"), McpRequestRegistry.currentSessionId());
        if (!Objects.equals(entry.kind(), kind)
                || !Objects.equals(entry.objectId(), fields.get("objectId"))
                || !entry.binding().equals(initial.binding())) throw new McpStoredSnapshotVerification.Stale();
        McpKeyformContinuity.identity(entry.objectId());
        McpKeyformContinuity.identity(entry.sourceGuid());
        return entry;
    }

    private void verify(
            McpAuthoringSnapshotStore.Entry source,
            McpAuthoringSnapshotStore.Entry target,
            McpStoredAuthoringDomain.Context initial) {
        McpStoredSnapshotVerification.verify(source, initial, context);
        McpStoredSnapshotVerification.verify(target, initial, context);
    }

    private static Map<String, Object> reference(McpAuthoringSnapshotStore.Entry entry, String form) {
        return Map.of(
                "objectId",
                entry.objectId(),
                "snapshotId",
                entry.snapshotId(),
                "sourceGuid",
                entry.sourceGuid(),
                "defaultFormGuid",
                form);
    }

    private static float key(Object raw) {
        float value = (float) number(raw);
        if (!Float.isFinite(value)
                || new java.math.BigDecimal(raw.toString()).compareTo(new java.math.BigDecimal(Float.toString(value)))
                        != 0) throw new IllegalArgumentException();
        return value;
    }

    private static double number(Object raw) {
        if (!(raw instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException();
        return n.doubleValue();
    }

    private static int bytes(Map<String, ?> value) {
        return Json.stringify(value).getBytes(StandardCharsets.UTF_8).length;
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
}
