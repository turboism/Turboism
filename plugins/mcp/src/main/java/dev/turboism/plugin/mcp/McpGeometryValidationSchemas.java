package dev.turboism.plugin.mcp;

import java.util.*;

final class McpGeometryValidationSchemas {
    static Map<String, Object> definition() {
        return Map.of(
                "name",
                McpGeometryValidationDomain.VALIDATE,
                "title",
                "Validate scoped stored ArtMesh geometry",
                "description",
                "Read-only geometric diagnostics, not model acceptance. Use an issued meshes.read snapshot and explicit stored referenceFormGuid for form comparisons; source coordinates are never assumed equivalent to stored ones. source-only requires formGuids=[] and referenceFormGuid=null. Only ordinary cell forms not referenced by Morph are comparable; default/shared ordinary forms may be read. Area tolerance uses absolute signed double-area / bounding-box diagonal squared. Aspect is sum(edgeLengthSquared)/(2*sqrt(3)*abs(doubleArea)), equilateral=1. Ratios compare native stored forms of the same object, not evaluated meshes. Positive-area triangle interior overlaps use a bounded AABB sweep and robust separating-axis predicates, including exact binary-float fallback near roundoff. Shared boundaries alone are not findings; disconnected containment and adjacent fold-over are. Pair findings locate triangleIndex/relatedTriangleIndex. Boundary contacts, cross-object intersections and cracks remain omitted. Budget exhaustion marks intersectionCheckComplete/analysisComplete/comparisonComplete false and verdict INCOMPLETE, with tested-pair/sweep/work statistics. Report exact implemented/omitted checks, truncation and skipped comparisons; no findings is scoped to these checks. Optional positions-only proposedEdits reuse ordinary writer preflight without committing, can replace only selected targets and never the explicit reference. Stored source/UV/indices and draw order remain unchanged. Max 32 forms, 200 retained findings, 2M estimated work, 240KiB result. No writes, rendering or automatic repair.",
                "inputSchema",
                input(),
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(
                                output(),
                                object(Map.of(
                                        "ok",
                                        Map.of("const", false),
                                        "error",
                                        object(Map.of("code", string(), "message", string())))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    private static Map<String, Object> input() {
        var fields = new LinkedHashMap<String, Object>();
        for (String key : List.of("documentId", "modelId", "objectId", "snapshotId")) fields.put(key, string());
        fields.put("includeSource", Map.of("type", "boolean"));
        fields.put("referenceFormGuid", nullableString());
        fields.put("formGuids", array(string(), 32));
        fields.put("thresholds", thresholds());
        fields.put("maxFindings", Map.of("type", "integer", "minimum", 1, "maximum", 200));
        var required = List.copyOf(fields.keySet());
        var point = object(Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number")));
        var coordinate = object(Map.of("parameterGuid", string(), "value", Map.of("type", "number")));
        fields.put(
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
                                array(coordinate, 64),
                                "positions",
                                array(point, 16384)))));
        var input = new LinkedHashMap<>(object(fields));
        input.put("required", required);
        return input;
    }

    private static Map<String, Object> thresholds() {
        return object(Map.of(
                "minimumAreaRatio",
                Map.of("type", "number", "minimum", 0, "maximum", 1),
                "maximumEdgeStretch",
                Map.of("type", "number", "minimum", 1, "maximum", 1000),
                "minimumEdgeCompression",
                Map.of("type", "number", "exclusiveMinimum", 0, "maximum", 1),
                "maximumTriangleAspectRatio",
                Map.of("type", "number", "minimum", 1, "maximum", 1000)));
    }

    private static Map<String, Object> output() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("ok", Map.of("const", true));
        for (String key : List.of("objectId", "snapshotId", "sourceGuid", "coordinateSpace")) fields.put(key, string());
        var state = new LinkedHashMap<String, Object>();
        for (String key : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            state.put(key, Map.of("type", "string"));
        state.put("historyGeneration", integer());
        state.put("historyRevision", integer());
        fields.put("state", object(state));
        fields.put("proposedEditCount", integer());
        fields.put("includeSource", Map.of("type", "boolean"));
        fields.put("referenceFormGuid", nullableString());
        fields.put("checkedForms", array(string(), 32));
        fields.put("thresholds", thresholds());
        fields.put("implementedChecks", array(string(), 32));
        fields.put("notImplementedChecks", array(string(), 32));
        fields.put("verdict", Map.of("enum", List.of("NO_FINDINGS_FOR_IMPLEMENTED_CHECKS", "FINDINGS", "INCOMPLETE")));
        fields.put("analysisComplete", Map.of("type", "boolean"));
        fields.put("intersectionCheckComplete", Map.of("type", "boolean"));
        fields.put("comparisonComplete", Map.of("type", "boolean"));
        fields.put("findingsTruncated", Map.of("type", "boolean"));
        fields.put("findings", array(finding(), 200));
        var stats = new LinkedHashMap<String, Object>();
        for (String key : List.of(
                "vertices",
                "trianglesPerGeometry",
                "uniqueEdges",
                "checkedGeometries",
                "findingCount",
                "retainedFindingCount",
                "skippedComparisons",
                "intersectionPairsTested",
                "intersectionSweepVisits",
                "estimatedWork")) stats.put(key, integer());
        fields.put("statistics", object(stats));
        return object(fields);
    }

    private static Map<String, Object> finding() {
        return object(Map.of(
                "code",
                string(),
                "severity",
                Map.of("enum", List.of("ERROR", "WARNING")),
                "scope",
                Map.of("enum", List.of("source", "stored", "reference", "topology")),
                "formGuid",
                nullableString(),
                "triangleIndex",
                Map.of("type", List.of("integer", "null")),
                "relatedTriangleIndex",
                Map.of("type", List.of("integer", "null")),
                "indices",
                array(integer(), 3),
                "observed",
                Map.of("type", List.of("number", "null")),
                "threshold",
                Map.of("type", List.of("number", "null"))));
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

    private static Map<String, Object> string() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096);
    }

    private static Map<String, Object> nullableString() {
        return Map.of("type", List.of("string", "null"));
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer", "minimum", 0);
    }
}
