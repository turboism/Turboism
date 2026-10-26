package dev.turboism.plugin.mcp;

import java.util.*;

final class McpWarpValidationSchemas {
    static Map<String, Object> definition() {
        var input = new LinkedHashMap<String, Object>();
        for (String k : List.of("documentId", "modelId", "objectId", "snapshotId", "referenceFormGuid"))
            input.put(k, string());
        input.put("formGuids", Map.of("type", "array", "minItems", 1, "maxItems", 32, "items", string()));
        input.put("minimumAbsJacobianRatio", Map.of("type", "number", "minimum", 0, "maximum", 1));
        input.put("maxFindings", Map.of("type", "integer", "minimum", 1, "maximum", 200));
        var required = List.copyOf(input.keySet());
        var coordinate = object(Map.of("parameterGuid", string(), "value", Map.of("type", "number")));
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
                                string(),
                                "coordinates",
                                array(coordinate, 16),
                                "controlPoints",
                                array(point, 16384)))));
        var in = new LinkedHashMap<>(object(input));
        in.put("required", required);
        var output = new LinkedHashMap<String, Object>();
        output.put("ok", Map.of("const", true));
        for (String k : List.of("objectId", "snapshotId", "sourceGuid", "coordinateSpace", "referenceFormGuid"))
            output.put(k, string());
        var state = new LinkedHashMap<String, Object>();
        for (String k : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            state.put(k, Map.of("type", "string"));
        state.put("historyGeneration", integer());
        state.put("historyRevision", integer());
        output.put("state", object(state));
        output.put("checkedForms", array(string(), 32));
        output.put("proposedEditCount", integer());
        output.put("minimumAbsJacobianRatio", Map.of("type", "number", "minimum", 0, "maximum", 1));
        output.put("implementedChecks", array(string(), 32));
        output.put("notImplementedChecks", array(string(), 32));
        output.put("verdict", Map.of("enum", List.of("NO_FINDINGS_FOR_IMPLEMENTED_CHECKS", "FINDINGS")));
        for (String k : List.of("analysisComplete", "comparisonComplete", "findingsTruncated"))
            output.put(k, Map.of("type", "boolean"));
        output.put(
                "findings",
                array(
                        object(Map.of(
                                "code",
                                string(),
                                "severity",
                                Map.of("const", "WARNING"),
                                "scope",
                                Map.of("enum", List.of("reference", "target")),
                                "formGuid",
                                string(),
                                "row",
                                integer(),
                                "column",
                                integer(),
                                "controlPointIndices",
                                array(integer(), 4),
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
                "retainedFindingCount")) stats.put(k, integer());
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
                                        object(Map.of("code", string(), "message", string())))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    private static Map<String, Object> object(Map<String, Object> fields) {
        return Map.of(
                "type",
                "object",
                "properties",
                fields,
                "required",
                List.copyOf(fields.keySet()),
                "additionalProperties",
                false);
    }

    private static Map<String, Object> array(Map<String, Object> items, int max) {
        return Map.of("type", "array", "items", items, "maxItems", max);
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer", "minimum", 0);
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096);
    }
}
