package dev.turboism.plugin.mcp;

import java.util.*;

final class McpContinuitySchemas {
    static Map<String, Object> definition() {
        var alternatives = new ArrayList<Object>();
        for (String kind : List.of("art_mesh", "warp_deformer", "rotation_deformer")) {
            var properties = new LinkedHashMap<String, Object>();
            for (String field : List.of("documentId", "modelId", "objectId", "snapshotId"))
                properties.put(field, text());
            properties.put("kind", Map.of("const", kind));
            properties.put("thresholds", thresholds());
            properties.put("maxFindings", integer(1, 200));
            var required = List.copyOf(properties.keySet());
            var edit = new LinkedHashMap<String, Object>();
            edit.put("formGuid", text());
            edit.put("coordinates", array(coordinate(), 3));
            if (kind.equals("rotation_deformer")) {
                var form = new LinkedHashMap<String, Object>();
                for (String field : List.of("angle", "originX", "originY")) form.put(field, number());
                form.put("scale", Map.of("type", "number", "exclusiveMinimum", 0));
                for (String field : List.of("reflectedX", "reflectedY")) form.put(field, Map.of("type", "boolean"));
                edit.put("form", object(form));
            } else
                edit.put(
                        kind.equals("art_mesh") ? "positions" : "controlPoints",
                        array(object(Map.of("x", number(), "y", number())), 4096));
            properties.put(
                    "proposedEdits", Map.of("type", "array", "minItems", 1, "maxItems", 64, "items", object(edit)));
            var branch = new LinkedHashMap<>(object(properties));
            branch.put("required", required);
            alternatives.add(branch);
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("ok", Map.of("const", true));
        for (String key : List.of("objectId", "snapshotId", "sourceGuid", "coordinateSpace")) output.put(key, text());
        output.put("kind", Map.of("enum", List.of("art_mesh", "warp_deformer", "rotation_deformer")));
        var state = new LinkedHashMap<String, Object>();
        for (String key : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            state.put(key, Map.of("type", "string"));
        for (String key : List.of("historyGeneration", "historyRevision")) state.put(key, integer(0, Long.MAX_VALUE));
        output.put("state", object(state));
        output.put("thresholds", thresholds());
        output.put(
                "axes",
                array(
                        object(Map.of(
                                "parameterGuid",
                                text(),
                                "parameterId",
                                text(),
                                "minimum",
                                number(),
                                "defaultValue",
                                number(),
                                "maximum",
                                number(),
                                "keys",
                                array(number(), 9))),
                        3));
        output.put("proposedEditCount", integer(0, 64));
        output.put("implementedChecks", array(text(), 16));
        output.put("notImplementedChecks", array(text(), 16));
        output.put("verdict", Map.of("enum", List.of("FINDINGS", "NO_FINDINGS_FOR_IMPLEMENTED_CHECKS")));
        output.put("analysisComplete", Map.of("const", true));
        output.put("findingsTruncated", Map.of("type", "boolean"));
        var finding = new LinkedHashMap<String, Object>();
        finding.put("parameterGuid", text());
        finding.put("centerCoordinates", array(coordinate(), 3));
        finding.put("formGuids", Map.of("type", "array", "minItems", 3, "maxItems", 3, "items", text()));
        finding.put("axisValues", Map.of("type", "array", "minItems", 3, "maxItems", 3, "items", number()));
        finding.put(
                "code",
                Map.of(
                        "enum",
                        List.of(
                                "POSITION_SLOPE_CHANGE",
                                "ANGLE_SLOPE_CHANGE",
                                "SCALE_SLOPE_CHANGE",
                                "REFLECTION_CHANGE")));
        finding.put("severity", Map.of("const", "WARNING"));
        finding.put(
                "component",
                Map.of("enum", List.of("positions", "origin", "angle", "scale", "reflectedX", "reflectedY")));
        finding.put("pointIndex", Map.of("oneOf", List.of(integer(0, 4095), Map.of("type", "null"))));
        finding.put("observed", Map.of("type", "number", "minimum", 0));
        finding.put("threshold", Map.of("type", "number", "minimum", 0));
        output.put("findings", array(object(finding), 200));
        var stats = new LinkedHashMap<String, Object>();
        for (String key : List.of("triples", "pointComparisons", "findingCount", "retainedFindingCount"))
            stats.put(key, integer(0, Long.MAX_VALUE));
        for (String key : List.of("maximumPositionSlopeChange", "maximumAngleSlopeChange", "maximumScaleSlopeChange"))
            stats.put(
                    key,
                    key.equals("maximumPositionSlopeChange")
                            ? Map.of("type", "number", "minimum", 0)
                            : Map.of("oneOf", List.of(Map.of("type", "number", "minimum", 0), Map.of("type", "null"))));
        output.put("statistics", object(stats));
        return Map.of(
                "name",
                McpContinuityDomain.VALIDATE,
                "title",
                "Inspect ordinary keyform slope changes",
                "description",
                "Read-only stored/proposed ordinary ArtMesh/Warp/Rotation secant-slope diagnostics using an issued authoring snapshot. Requires a complete Cartesian grid of 1..3 axes, 3..9 distinct in-range keys each, at most27 cells/forms,4096 points/form,32768 stored point samples. Shared/default ordinary forms may be READ; ArtMesh Morph snapshots are rejected, Warp/Rotation snapshots do not describe Morph combinations. All adjacent triples along every axis are checked holding all other GUID coordinates fixed. Slope is delta stored value / (delta axis key / full parameter range); findings compare right minus left slope. Position uses Euclidean norm, angle raw native degrees without wrapping, scale raw scalar; reflection changes always warn. Thresholds are explicit 0..1e12, not aesthetic defaults. Output includes actual axis ranges/keys; rotation-only maxima are null for point geometry, not measured zero. Nonuniform keys use their actual intervals, never collection order or display IDs. This detects changes between secants, not C0/C1/native interpolator continuity or between-key geometry safety; intentional curvature may warn. Native stored coordinates remain local, no parent/pixel/evaluated conversions. DrawOrder/opacity, mixed derivatives, Morph and visual quality omitted. Optional closed positions/controlPoints/full Rotation proposedEdits reuse ordinary writer preflight and cannot edit default/shared forms; no commit. Checks native full snapshot/parents/history before and after analysis, no pose/selection/render/write. At most200 retained warnings but all scoped comparisons counted; findingsTruncated is explicit, result240KiB, request768KiB. Oversized/unsupported/incomplete grids fail without verdict; no findings is not model acceptance.",
                "inputSchema",
                Map.of("oneOf", alternatives),
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(
                                object(output),
                                object(Map.of(
                                        "ok",
                                        Map.of("const", false),
                                        "error",
                                        object(Map.of("code", text(), "message", text())))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    private static Map<String, Object> coordinate() {
        return object(Map.of("parameterGuid", text(), "value", number()));
    }

    private static Map<String, Object> thresholds() {
        var n = Map.of("type", "number", "minimum", 0, "maximum", 1e12);
        return object(
                Map.of("maximumPositionSlopeChange", n, "maximumAngleSlopeChange", n, "maximumScaleSlopeChange", n));
    }

    private static Map<String, Object> number() {
        return Map.of("type", "number");
    }

    private static Map<String, Object> integer(long low, long high) {
        return Map.of("type", "integer", "minimum", low, "maximum", high);
    }

    private static Map<String, Object> text() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096);
    }

    private static Map<String, Object> array(Object items, int maximum) {
        return Map.of("type", "array", "items", items, "maxItems", maximum);
    }

    private static Map<String, Object> object(Map<String, ?> fields) {
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
}
