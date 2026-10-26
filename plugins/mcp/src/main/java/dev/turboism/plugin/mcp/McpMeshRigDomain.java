package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Read-only rig building block; submitting a preview never authorizes or performs a write. */
final class McpMeshRigDomain {
    static final String PLAN = "turboism.rig.mesh.plan";
    private final McpAuthoringSnapshotStore snapshots;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;

    McpMeshRigDomain(
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
                McpMeshRigSchemas.definition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.UI_THREAD,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-model.objects.read",
                        versions,
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "landmark_mesh_plan",
                                        McpOperationEffect.READ,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        versions,
                                        "Read-only landmark displacement field planning on a complete one to three axis stored ArtMesh grid; no anatomical or visual acceptance."))),
                execution,
                this::plan)));
    }

    private Map<String, Object> plan(Map<String, Object> args) {
        try {
            var required = Set.of("documentId", "modelId", "objectId", "snapshotId", "operationId", "axes", "policy");
            var allowed = new HashSet<>(required);
            allowed.add("cornerCorrections");
            if (!args.keySet().containsAll(required) || !allowed.containsAll(args.keySet()))
                throw new IllegalArgumentException();
            for (String field : List.of("documentId", "modelId", "objectId", "snapshotId", "operationId"))
                text(args.get(field));
            if (!"REPLACE_POSITIONS_FROM_DEFAULT".equals(args.get("policy"))) throw new IllegalArgumentException();
            var initial = context.get();
            if (!args.get("documentId").equals(initial.binding().get("documentId"))
                    || !args.get("modelId").equals(initial.binding().get("modelId"))) throw new Stale();
            var snapshot = snapshots.get(text(args.get("snapshotId")), McpRequestRegistry.currentSessionId());
            if (!"art_mesh".equals(snapshot.kind())
                    || !(snapshot.nativeSnapshot() instanceof ArtMeshAuthoringSnapshot mesh))
                throw new UnsupportedOperationException();
            if (!snapshot.binding().equals(initial.binding())
                    || !snapshot.objectId().equals(args.get("objectId"))
                    || !mesh.artMeshId().value().equals(snapshot.objectId())
                    || !mesh.sourceGuid().equals(snapshot.sourceGuid())) throw new Stale();
            verify(snapshot, initial);
            var corrections = corners(args.getOrDefault("cornerCorrections", List.of()));
            var edits = McpMeshRigPlan.edits(mesh, axes(args.get("axes")), corrections);
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
                            "art_mesh",
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
                    "cornerCorrectionCount",
                    corrections.size(),
                    "writeTool",
                    McpKeyformWriteDomain.WRITE,
                    "writeRequest",
                    request,
                    "limitations",
                    List.of(
                            "NATIVE_STORED_COORDINATES_ONLY",
                            "REPLACES_EXISTING_TARGET_POSITIONS",
                            "PAIR_TRIPLE_CORRECTIONS_REQUIRE_EXPLICIT_LANDMARKS",
                            "STORED_KEY_PLAN_NOT_EVALUATED_INTERPOLATION",
                            "NO_ANATOMICAL_INFERENCE_OR_VISUAL_ACCEPTANCE",
                            "WRITE_REQUIRES_FRESH_CAS_AND_SEPARATE_AUTHORIZATION"));
            if (Json.stringify(output).getBytes(StandardCharsets.UTF_8).length > 240 * 1024) throw new BudgetExceeded();
            verify(snapshot, initial);
            return envelope(output);
        } catch (RuntimeException failure) {
            String code = failure instanceof BudgetExceeded
                    ? "BUDGET_EXCEEDED"
                    : failure instanceof Stale || failure instanceof McpStoredAuthoringDomain.Stale
                            ? "STALE_STATE"
                            : failure instanceof McpStoredAuthoringDomain.NoModel
                                    ? "NO_ACTIVE_MODEL"
                                    : failure instanceof NoSuchElementException
                                            ? "NOT_FOUND"
                                            : failure instanceof SecurityException
                                                            || failure
                                                                    instanceof
                                                                    dev.turboism.sdk.permission
                                                                            .CubismPermissionException
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
                                    "ArtMesh plan rejected; rediscover identity, supported scope and snapshots. No write performed")));
        }
    }

    private void verify(McpAuthoringSnapshotStore.Entry snapshot, McpStoredAuthoringDomain.Context initial) {
        var now = context.get();
        if (!initial.binding().equals(now.binding()) || !snapshot.binding().equals(now.binding())) throw new Stale();
        var mesh = now.model().drawables().find(new ArtMeshId(snapshot.objectId()));
        if (!snapshot.nativeSnapshot().equals(mesh.authoringSnapshot())) throw new Stale();
        var parent = snapshot.rows().stream()
                .filter(row -> "parent_space".equals(row.get("kind")))
                .findFirst()
                .orElseThrow(Stale::new);
        if (!Objects.equals(
                        parent.get("parentPartId"),
                        mesh.parentPartId().map(v -> v.value()).orElse(null))
                || !Objects.equals(
                        parent.get("parentDeformerId"),
                        mesh.parentDeformerId().map(v -> v.value()).orElse(null))) throw new Stale();
        if (!initial.binding().equals(context.get().binding())) throw new Stale();
    }

    private static List<McpLandmarkRigPlan.Corner> corners(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() > 20) throw new IllegalArgumentException();
        var result = new ArrayList<McpLandmarkRigPlan.Corner>();
        for (var value : values) {
            if (!(value instanceof Map<?, ?> corner)
                    || !corner.keySet().equals(Set.of("directions", "anchors"))
                    || !(corner.get("directions") instanceof List<?> directions)
                    || (directions.size() < 2 || directions.size() > 3)) throw new IllegalArgumentException();
            var sides = new HashMap<String, Boolean>();
            for (var direction : directions) {
                if (!(direction instanceof Map<?, ?> axis)
                        || !axis.keySet().equals(Set.of("parameterGuid", "side"))
                        || !Set.of("NEGATIVE", "POSITIVE").contains(axis.get("side")))
                    throw new IllegalArgumentException();
                if (sides.putIfAbsent(text(axis.get("parameterGuid")), "POSITIVE".equals(axis.get("side"))) != null)
                    throw new IllegalArgumentException();
            }
            result.add(new McpLandmarkRigPlan.Corner(Map.copyOf(sides), anchors(corner.get("anchors"))));
        }
        return List.copyOf(result);
    }

    private static List<McpLandmarkRigPlan.Axis> axes(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 3)
            throw new IllegalArgumentException();
        var result = new ArrayList<McpLandmarkRigPlan.Axis>();
        for (var value : values) {
            if (!(value instanceof Map<?, ?> axis)
                    || !axis.keySet().equals(Set.of("parameterGuid", "negativeAnchors", "positiveAnchors")))
                throw new IllegalArgumentException();
            result.add(new McpLandmarkRigPlan.Axis(
                    text(axis.get("parameterGuid")),
                    anchors(axis.get("negativeAnchors")),
                    anchors(axis.get("positiveAnchors"))));
        }
        return List.copyOf(result);
    }

    private static List<McpLandmarkRigPlan.Anchor> anchors(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty() || values.size() > 16)
            throw new IllegalArgumentException();
        var result = new ArrayList<McpLandmarkRigPlan.Anchor>();
        for (var value : values) {
            if (!(value instanceof Map<?, ?> anchor)
                    || !anchor.keySet().equals(Set.of("vertexIndex", "dx", "dy"))
                    || !(anchor.get("vertexIndex") instanceof Number index)
                    || !(anchor.get("dx") instanceof Number x)
                    || !(anchor.get("dy") instanceof Number y)) throw new IllegalArgumentException();
            int point = new java.math.BigDecimal(index.toString()).intValueExact();
            result.add(new McpLandmarkRigPlan.Anchor(point, x.doubleValue(), y.doubleValue()));
        }
        return List.copyOf(result);
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

    private static final class BudgetExceeded extends RuntimeException {}

    private static final class Stale extends RuntimeException {}
}
