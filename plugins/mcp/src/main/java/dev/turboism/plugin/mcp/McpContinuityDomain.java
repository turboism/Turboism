package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Read-only live-verified stored keyform slope diagnostics, never an evaluated quality gate. */
final class McpContinuityDomain {
    static final String VALIDATE = "turboism.validation.keyform_continuity";
    private final McpAuthoringSnapshotStore snapshots;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;

    McpContinuityDomain(
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
                McpContinuitySchemas.definition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.UI_THREAD,
                false,
                McpVersionSupport.exact(
                        "cubism.editor-model.objects.read",
                        versions,
                        List.of(
                                McpVersionSupport.OperationSupport.available(
                                        "stored_keyform_slope_diagnostics",
                                        McpOperationEffect.READ,
                                        false,
                                        McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                        versions,
                                        "Ordinary native stored metadata only; secant slope proxy, not native interpolation or visual acceptance."))),
                execution,
                this::validate)));
    }

    private Map<String, Object> validate(Map<String, Object> args) {
        try {
            if (bytes(args) > 768 * 1024) throw new McpBindingGridSnapshot.Budget();
            var initial = context.get();
            if (!Objects.equals(args.get("documentId"), initial.binding().get("documentId"))
                    || !Objects.equals(args.get("modelId"), initial.binding().get("modelId"))) throw new Stale();
            var entry = snapshots.get((String) args.get("snapshotId"), McpRequestRegistry.currentSessionId());
            if (!Objects.equals(entry.kind(), args.get("kind"))
                    || !Objects.equals(entry.objectId(), args.get("objectId"))
                    || !entry.binding().equals(initial.binding())) throw new Stale();
            McpKeyformContinuity.identity(entry.sourceGuid());
            McpKeyformContinuity.identity(entry.objectId());
            var t = (Map<?, ?>) args.get("thresholds");
            var thresholds = new McpKeyformContinuity.Thresholds(
                    number(t.get("maximumPositionSlopeChange")),
                    number(t.get("maximumAngleSlopeChange")),
                    number(t.get("maximumScaleSlopeChange")));
            int maximum = new java.math.BigDecimal(args.get("maxFindings").toString()).intValueExact();
            McpStoredSnapshotVerification.verify(entry, initial, context);
            Object analyzed = entry.nativeSnapshot();
            int count = 0;
            if (args.containsKey("proposedEdits")) {
                var edits = (List<?>) args.get("proposedEdits");
                analyzed = McpKeyformWritePlan.prepare(analyzed, edits).after();
                count = edits.size();
            }
            var report = McpKeyformContinuity.analyze(analyzed, thresholds, maximum);
            McpStoredSnapshotVerification.verify(entry, initial, context);
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", true);
            output.put("kind", entry.kind());
            output.put("objectId", entry.objectId());
            output.put("snapshotId", entry.snapshotId());
            output.put("sourceGuid", entry.sourceGuid());
            output.put("state", initial.binding());
            output.put(
                    "coordinateSpace",
                    "Native stored positions/origins, raw angle degrees and scalar scale; parameter intervals normalized by full declared range. No parent/evaluated/pixel conversion or angle wrapping.");
            output.put("thresholds", t);
            output.put(
                    "axes",
                    McpBindingGridSnapshot.view(analyzed).axes().values().stream()
                            .sorted(Comparator.comparing(McpBindingGridSnapshot.Axis::guid))
                            .map(McpBindingGridSnapshot.Axis::wire)
                            .toList());
            output.put("proposedEditCount", count);
            output.put(
                    "implementedChecks",
                    entry.kind().equals("rotation_deformer")
                            ? McpKeyformContinuity.IMPLEMENTED
                            : List.of(McpKeyformContinuity.IMPLEMENTED.get(0)));
            output.put("notImplementedChecks", McpKeyformContinuity.OMITTED);
            output.put("verdict", report.findingCount() == 0 ? "NO_FINDINGS_FOR_IMPLEMENTED_CHECKS" : "FINDINGS");
            output.put("analysisComplete", true);
            output.put(
                    "findingsTruncated",
                    report.findingCount() > report.findings().size());
            output.put("findings", report.findings());
            var stats = new LinkedHashMap<String, Object>();
            stats.put("triples", report.triples());
            stats.put("pointComparisons", report.pointComparisons());
            stats.put("findingCount", report.findingCount());
            stats.put("retainedFindingCount", report.findings().size());
            stats.put("maximumPositionSlopeChange", report.maximumPositionSlopeChange());
            boolean rotation = entry.kind().equals("rotation_deformer");
            stats.put("maximumAngleSlopeChange", rotation ? report.maximumAngleSlopeChange() : null);
            stats.put("maximumScaleSlopeChange", rotation ? report.maximumScaleSlopeChange() : null);
            output.put("statistics", stats);
            if (bytes(output) > 240 * 1024) throw new McpBindingGridSnapshot.Budget();
            return envelope(output);
        } catch (RuntimeException failure) {
            String code = failure instanceof Stale
                            || failure instanceof McpStoredSnapshotVerification.Stale
                            || failure instanceof McpStoredAuthoringDomain.Stale
                    ? "STALE_STATE"
                    : failure instanceof McpStoredAuthoringDomain.NoModel
                            ? "NO_ACTIVE_MODEL"
                            : failure instanceof NoSuchElementException
                                    ? "NOT_FOUND"
                                    : failure instanceof McpBindingGridSnapshot.Budget
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
                                    "Stored slope diagnostics rejected; no verdict produced. Rediscover target and authoring snapshot.")));
        }
    }

    private static double number(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException();
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

    private static final class Stale extends IllegalStateException {}
}
