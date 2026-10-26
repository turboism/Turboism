package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Read-only findings on an explicitly scoped, live-verified immutable ArtMesh snapshot. */
final class McpGeometryValidationDomain {
    static final String VALIDATE = "turboism.validation.geometry";
    private final McpAuthoringSnapshotStore snapshots;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;

    McpGeometryValidationDomain(
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
                McpGeometryValidationSchemas.definition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.UI_THREAD,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-model.objects.read",
                        versions,
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "stored_artmesh_quality",
                                        McpOperationEffect.READ,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        versions,
                                        "Source/stored ArtMesh diagnostics including bounded robust positive-area triangle overlap on live-verified immutable snapshots. Explicit stored reference, ordinary-only comparisons; budget-limited overlap is INCOMPLETE. No boundary contacts, cross-object/evaluated geometry or visual acceptance."))),
                execution,
                this::validate)));
    }

    private Map<String, Object> validate(Map<String, Object> args) {
        try {
            var required = Set.of(
                    "documentId",
                    "modelId",
                    "objectId",
                    "snapshotId",
                    "includeSource",
                    "referenceFormGuid",
                    "formGuids",
                    "thresholds",
                    "maxFindings");
            var allowed = new HashSet<>(required);
            allowed.add("proposedEdits");
            if (!args.keySet().containsAll(required) || !allowed.containsAll(args.keySet()))
                throw new IllegalArgumentException();
            var initial = context.get();
            if (!text(args.get("documentId")).equals(initial.binding().get("documentId"))
                    || !text(args.get("modelId")).equals(initial.binding().get("modelId"))) throw new Stale();
            String id = text(args.get("objectId")), sid = text(args.get("snapshotId"));
            var snapshot = snapshots.get(sid, McpRequestRegistry.currentSessionId());
            if (!snapshot.kind().equals("art_mesh")
                    || !(snapshot.nativeSnapshot() instanceof ArtMeshAuthoringSnapshot mesh))
                throw new UnsupportedOperationException("Only ArtMesh supported");
            if (!snapshot.objectId().equals(id)
                    || !snapshot.binding().equals(initial.binding())
                    || !mesh.artMeshId().value().equals(id)
                    || !mesh.sourceGuid().equals(snapshot.sourceGuid())) throw new Stale();
            if (!(args.get("includeSource") instanceof Boolean includeSource)) throw new IllegalArgumentException();
            String reference = args.get("referenceFormGuid") == null ? null : text(args.get("referenceFormGuid"));
            if (!(args.get("formGuids") instanceof List<?> values) || values.size() > 32)
                throw new IllegalArgumentException();
            var selected =
                    values.stream().map(McpGeometryValidationDomain::text).toList();
            var rawThresholds = fields(
                    args.get("thresholds"),
                    "minimumAreaRatio",
                    "maximumEdgeStretch",
                    "minimumEdgeCompression",
                    "maximumTriangleAspectRatio");
            var thresholds = new McpGeometryQuality.Thresholds(
                    number(rawThresholds.get("minimumAreaRatio")),
                    number(rawThresholds.get("maximumEdgeStretch")),
                    number(rawThresholds.get("minimumEdgeCompression")),
                    number(rawThresholds.get("maximumTriangleAspectRatio")));
            int maxFindings = integer(args.get("maxFindings"));
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", true);
            output.put("objectId", id);
            output.put("snapshotId", sid);
            output.put("sourceGuid", text(snapshot.sourceGuid()));
            output.put("state", initial.binding());
            output.put("includeSource", includeSource);
            output.put("referenceFormGuid", reference);
            output.put("checkedForms", selected);
            output.put("thresholds", rawThresholds);
            if (Json.stringify(output).getBytes(StandardCharsets.UTF_8).length > 64 * 1024)
                throw new McpGeometryQuality.BudgetExceeded();
            verify(snapshot, initial);
            ArtMeshAuthoringSnapshot analyzed = mesh;
            List<?> proposed = List.of();
            if (args.containsKey("proposedEdits")) {
                if (!(args.get("proposedEdits") instanceof List<?> edits) || edits.isEmpty() || reference == null)
                    throw new IllegalArgumentException();
                proposed = edits;
                for (var edit : proposed) fields(edit, "formGuid", "coordinates", "positions");
                var plan = (McpKeyformWritePlan.Mesh) McpKeyformWritePlan.prepare(mesh, proposed);
                var requested = new HashSet<>(selected);
                if (plan.edits().stream()
                        .anyMatch(e -> e.formGuid().equals(reference) || !requested.contains(e.formGuid())))
                    throw new IllegalArgumentException();
                analyzed = plan.after();
            }
            output.put("proposedEditCount", proposed.size());
            var report =
                    McpGeometryQuality.analyze(analyzed, includeSource, reference, selected, thresholds, maxFindings);
            verify(snapshot, initial);
            output.put(
                    "coordinateSpace",
                    "Native source/stored coordinates analyzed separately. Only stored forms of this object are compared; no pixel/evaluated/canvas conversion.");
            output.put("implementedChecks", McpGeometryQuality.IMPLEMENTED);
            output.put("notImplementedChecks", McpGeometryQuality.NOT_IMPLEMENTED);
            output.put("verdict", report.verdict());
            output.put("analysisComplete", report.intersectionCheckComplete());
            output.put("intersectionCheckComplete", report.intersectionCheckComplete());
            output.put("comparisonComplete", report.skippedComparisons() == 0 && report.intersectionCheckComplete());
            output.put("findingsTruncated", report.findingsTruncated());
            output.put("findings", report.findings());
            output.put(
                    "statistics",
                    Map.of(
                            "vertices",
                            report.vertices(),
                            "trianglesPerGeometry",
                            report.triangles(),
                            "uniqueEdges",
                            report.uniqueEdges(),
                            "checkedGeometries",
                            report.checkedGeometries(),
                            "findingCount",
                            report.findingCount(),
                            "retainedFindingCount",
                            report.findings().size(),
                            "skippedComparisons",
                            report.skippedComparisons(),
                            "intersectionPairsTested",
                            report.intersectionPairsTested(),
                            "intersectionSweepVisits",
                            report.intersectionSweepVisits(),
                            "estimatedWork",
                            report.estimatedWork()));
            if (Json.stringify(output).getBytes(StandardCharsets.UTF_8).length > 240 * 1024)
                throw new McpGeometryQuality.BudgetExceeded();
            return envelope(output);
        } catch (RuntimeException failure) {
            String code = failure instanceof Stale || failure instanceof McpStoredAuthoringDomain.Stale
                    ? "STALE_STATE"
                    : failure instanceof McpStoredAuthoringDomain.NoModel
                            ? "NO_ACTIVE_MODEL"
                            : failure instanceof NoSuchElementException
                                    ? "NOT_FOUND"
                                    : failure instanceof McpGeometryQuality.BudgetExceeded
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
                                    "Geometry validation unavailable or rejected; no acceptance verdict was produced. Rediscover scope and snapshots")));
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

    private static Map<String, Object> fields(Object raw, String... keys) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().equals(Set.of(keys))) throw new IllegalArgumentException();
        var result = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> result.put((String) k, v));
        return result;
    }

    private static String text(Object raw) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 4096)
            throw new IllegalArgumentException();
        return value;
    }

    private static double number(Object raw) {
        if (!(raw instanceof Number value) || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException();
        return value.doubleValue();
    }

    private static int integer(Object raw) {
        if (!(raw instanceof Number number)) throw new IllegalArgumentException();
        try {
            return new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private static final class Stale extends IllegalStateException {}
}
