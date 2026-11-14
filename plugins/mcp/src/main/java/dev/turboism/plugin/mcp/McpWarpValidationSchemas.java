package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpSchemas.*;

import java.util.*;

final class McpWarpValidationSchemas {
    static Map<String, Object> definition() {
        var input = new LinkedHashMap<String, Object>();
        for (String k : List.of("documentId", "modelId", "objectId", "snapshotId", "referenceFormGuid"))
            input.put(k, id());
        input.put("formGuids", Map.of("type", "array", "minItems", 1, "maxItems", 32, "items", id()));
        input.put("minimumAbsJacobianRatio", Map.of("type", "number", "minimum", 0, "maximum", 1));
        input.put("maxFindings", Map.of("type", "integer", "minimum", 1, "maximum", 200));
        var required = List.copyOf(input.keySet());
        var coordinate = object(Map.of("parameterGuid", id(), "value", Map.of("type", "number")));
        var point = object(Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number")));
        input.put(
                "proposedEdits",
                Map.of(
                        "type",
                        "array",
                        "minItems",
                        1,
                        "maxItems",
                        64,
                        "items",
                        object(Map.of(
                                "formGuid",
                                id(),
                                "coordinates",
                                boundedArray(coordinate, 16),
                                "controlPoints",
                                boundedArray(point, 16384)))));
        var in = new LinkedHashMap<>(object(input));
        in.put("required", required);
        var output = new LinkedHashMap<String, Object>();
        output.put("ok", Map.of("const", true));
        for (String k : List.of("objectId", "snapshotId", "sourceGuid", "coordinateSpace", "referenceFormGuid"))
            output.put(k, id());
        var state = new LinkedHashMap<String, Object>();
        for (String k : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            state.put(k, Map.of("type", "string"));
        state.put("historyGeneration", nonNegativeInteger());
        state.put("historyRevision", nonNegativeInteger());
        output.put("state", object(state));
        output.put("checkedForms", boundedArray(id(), 32));
        output.put("proposedEditCount", nonNegativeInteger());
        output.put("minimumAbsJacobianRatio", Map.of("type", "number", "minimum", 0, "maximum", 1));
        output.put("implementedChecks", boundedArray(id(), 32));
        output.put("notImplementedChecks", boundedArray(id(), 32));
        output.put("verdict", Map.of("enum", List.of("NO_FINDINGS_FOR_IMPLEMENTED_CHECKS", "FINDINGS")));
        for (String k : List.of("analysisComplete", "comparisonComplete", "findingsTruncated"))
            output.put(k, Map.of("type", "boolean"));
        output.put(
                "findings",
                boundedArray(
                        object(Map.of(
                                "code",
                                id(),
                                "severity",
                                Map.of("const", "WARNING"),
                                "scope",
                                Map.of("enum", List.of("reference", "target")),
                                "formGuid",
                                id(),
                                "row",
                                nonNegativeInteger(),
                                "column",
                                nonNegativeInteger(),
                                "controlPointIndices",
                                boundedArray(nonNegativeInteger(), 4),
                                "observedMinimumAbsJacobianRatio",
                                Map.of("type", "number"),
                                "threshold",
                                Map.of("type", "number"))),
                        200));
        var stats = new LinkedHashMap<String, Object>();
        for (String k : List.of(
                "cellsPerForm",
                "checkedFormCount",
                "cellSamples",
                "referenceIrregularCells",
                "skippedOrientationComparisons",
                "findingCount",
                "retainedFindingCount")) stats.put(k, nonNegativeInteger());
        output.put("statistics", object(stats));
        return Map.of(
                "name",
                McpWarpValidationDomain.VALIDATE,
                "title",
                "Inspect stored or proposed Warp control cells",
                "description",
                "Read-only diagnostics on an issued warps.read snapshot. Explicit ordinary reference and 1..32 target form GUIDs. Optional proposedEdits use ordinary keyforms.write preflight without writing; may replace only selected targets, never the reference. Analyze four exact binary-float bilinear Jacobian corner determinants per row-major control cell: mixed signs indicate a local fold, abs(det)/cell bounding-box diagonal squared at or below the explicit threshold indicates degeneracy, regular cells compare orientation to the reference. A globally mirrored regular reference is valid. This bilinear control-cell proxy is not Cubism native surface evaluation; quadTransform does not upgrade this claim. Default/shared ordinary forms may be observed; unreferenced forms are rejected. Irregular reference/target cells skip orientation comparison and set comparisonComplete=false. Max 8192 cell samples including reference, 200 retained findings, 240KiB output. Exceeding budget produces no verdict. Findings are warnings for review, not model acceptance or automatic repair; global overlaps, evaluated geometry, pose continuity and art quality are omitted. Full native/parent/document/history checks before and after; no setters, pose changes, rendering or transactions.",
                "inputSchema",
                in,
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(
                                object(output),
                                object(Map.of(
                                        "ok",
                                        Map.of("const", false),
                                        "error",
                                        object(Map.of("code", id(), "message", id())))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }
}
