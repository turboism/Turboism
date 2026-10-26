package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Read-only stored/proposed Warp control-cell diagnostics, not evaluated model acceptance. */
final class McpWarpValidationDomain {
    static final String VALIDATE = "turboism.validation.warp";
    private final McpAuthoringSnapshotStore snapshots;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;

    McpWarpValidationDomain(
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
                McpWarpValidationSchemas.definition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.UI_THREAD,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-model.objects.read",
                        versions,
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "stored_warp_quality",
                                        McpOperationEffect.READ,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        versions,
                                        "Stored or proposed Warp bilinear control-cell diagnostics, not native evaluated or visual acceptance."))),
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
                    "referenceFormGuid",
                    "formGuids",
                    "minimumAbsJacobianRatio",
                    "maxFindings");
            var allowed = new HashSet<>(required);
            allowed.add("proposedEdits");
            if (!args.keySet().containsAll(required) || !allowed.containsAll(args.keySet()))
                throw new IllegalArgumentException();
            var initial = context.get();
            if (!text(args.get("documentId")).equals(initial.binding().get("documentId"))
                    || !text(args.get("modelId")).equals(initial.binding().get("modelId"))) throw new Stale();
            String id = text(args.get("objectId")), sid = text(args.get("snapshotId"));
            var entry = snapshots.get(sid, McpRequestRegistry.currentSessionId());
            if (!"warp_deformer".equals(entry.kind())
                    || !(entry.nativeSnapshot() instanceof WarpAuthoringSnapshot warp))
                throw new UnsupportedOperationException();
            if (!entry.objectId().equals(id)
                    || !entry.binding().equals(initial.binding())
                    || !warp.deformerId().value().equals(id)
                    || !warp.sourceGuid().equals(entry.sourceGuid())) throw new Stale();
            String reference = text(args.get("referenceFormGuid"));
            if (!(args.get("formGuids") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 32)
                throw new IllegalArgumentException();
            var selected = raw.stream().map(McpWarpValidationDomain::text).toList();
            double threshold = number(args.get("minimumAbsJacobianRatio"));
            int max = integer(args.get("maxFindings"));
            verify(entry, initial);
            WarpAuthoringSnapshot analyzed = warp;
            List<?> proposed = List.of();
            if (args.containsKey("proposedEdits")) {
                if (!(args.get("proposedEdits") instanceof List<?> edits) || edits.isEmpty())
                    throw new IllegalArgumentException();
                proposed = edits;
                var plan = (McpKeyformWritePlan.Warp) McpKeyformWritePlan.prepare(warp, proposed);
                // A stable reference must not be silently replaced by the proposal.
                if (plan.edits().stream().anyMatch(e -> e.formGuid().equals(reference)))
                    throw new IllegalArgumentException();
                var requested = new HashSet<>(selected);
                if (plan.edits().stream().anyMatch(e -> !requested.contains(e.formGuid())))
                    throw new IllegalArgumentException();
                analyzed = plan.after();
            }
            var report = McpWarpQuality.analyze(analyzed, reference, selected, threshold, max);
            verify(entry, initial);
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", true);
            output.put("objectId", id);
            output.put("snapshotId", sid);
            output.put("sourceGuid", warp.sourceGuid());
            output.put("state", initial.binding());
            output.put("referenceFormGuid", reference);
            output.put("checkedForms", selected);
            output.put("proposedEditCount", proposed.size());
            output.put("minimumAbsJacobianRatio", threshold);
            output.put(
                    "coordinateSpace",
                    "Native stored row-major controls; bilinear proxy per cell. No native surface, parent, pixel or evaluated-space conversion.");
            output.put("implementedChecks", McpWarpQuality.IMPLEMENTED);
            output.put("notImplementedChecks", McpWarpQuality.OMITTED);
            output.put("verdict", report.findingCount() == 0 ? "NO_FINDINGS_FOR_IMPLEMENTED_CHECKS" : "FINDINGS");
            output.put("analysisComplete", true);
            output.put("comparisonComplete", report.skippedComparisons() == 0);
            output.put(
                    "findingsTruncated",
                    report.findingCount() > report.findings().size());
            output.put("findings", report.findings());
            output.put(
                    "statistics",
                    Map.of(
                            "cellsPerForm",
                            report.cellCount(),
                            "checkedFormCount",
                            selected.size(),
                            "cellSamples",
                            (long) report.cellCount() * (selected.size() + 1),
                            "referenceIrregularCells",
                            report.referenceIrregularCells(),
                            "skippedOrientationComparisons",
                            report.skippedComparisons(),
                            "findingCount",
                            report.findingCount(),
                            "retainedFindingCount",
                            report.findings().size()));
            if (Json.stringify(output).getBytes(StandardCharsets.UTF_8).length > 240 * 1024)
                throw new McpWarpQuality.BudgetExceeded();
            return envelope(output);
        } catch (RuntimeException failure) {
            String code = failure instanceof Stale || failure instanceof McpStoredAuthoringDomain.Stale
                    ? "STALE_STATE"
                    : failure instanceof McpStoredAuthoringDomain.NoModel
                            ? "NO_ACTIVE_MODEL"
                            : failure instanceof NoSuchElementException
                                    ? "NOT_FOUND"
                                    : failure instanceof McpWarpQuality.BudgetExceeded
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
                                    "Warp diagnostics rejected; no acceptance verdict produced. Rediscover scope and snapshots")));
        }
    }

    private void verify(McpAuthoringSnapshotStore.Entry snapshot, McpStoredAuthoringDomain.Context initial) {
        var now = context.get();
        if (!initial.binding().equals(now.binding()) || !snapshot.binding().equals(now.binding())) throw new Stale();
        var object = now.model().deformers().find(new DeformerId(snapshot.objectId()));
        if (!(object instanceof WarpDeformer mesh)) throw new Stale();
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
