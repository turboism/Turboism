package dev.turboism.plugin.mcp;

import java.util.*;

final class McpEvaluatedMeshSchemas {
    static Map<String, Object> definition() {
        var fields = new LinkedHashMap<String, Object>();
        for (String key : List.of("documentId", "modelId", "objectId", "authoringSnapshotId", "snapshotId"))
            fields.put(key, text());
        fields.put("offset", integer());
        fields.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 512));
        var input = Map.of(
                "type",
                "object",
                "properties",
                fields,
                "required",
                List.of("documentId", "modelId", "objectId", "authoringSnapshotId"),
                "additionalProperties",
                false);
        var output = new LinkedHashMap<String, Object>();
        output.put("ok", Map.of("const", true));
        for (String k : List.of("objectId", "sourceGuid", "snapshotId", "authoringSnapshotId", "coordinateSpace"))
            output.put(k, text());
        var state = new LinkedHashMap<String, Object>();
        for (String k : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            state.put(k, Map.of("type", "string"));
        for (String k : List.of("historyGeneration", "historyRevision")) state.put(k, integer());
        output.put("state", object(state));
        output.put("freshness", Map.of("const", "UNPROVEN"));
        output.put("rows", Map.of("type", "array", "items", rows(), "maxItems", 512));
        output.put("offset", integer());
        output.put("totalRows", integer());
        output.put("nextOffset", Map.of("type", List.of("integer", "null")));
        return Map.of(
                "name",
                McpEvaluatedMeshDomain.READ,
                "title",
                "Observe last calculated ArtMesh geometry",
                "description",
                "Read-only native evaluationSnapshot observation using a same-session meshes.read authoringSnapshotId. Does not evaluate or assert freshness/success; freshness is always UNPROVEN. Native passEntryTimeMillis is written at pass entry, not successful mesh completion. live/consumed counters are independent signed values; never compare them for equality. liveConsumedValuesMatch compares GUID/value maps only and does not prove successful evaluation. interpolatedIdentity is native object identity, not interpolation success. Native coordinateType is not Core/source/stored/pixel coordinates. Null calculated form returns no position rows; never substitutes stored geometry. Max 65536 positions, 2048 live and consumed parameters each, 4MiB immutable sample; 512 rows/240KiB page. TTL/cache/session budgets shared with authoring snapshots. Every page verifies stored state/parents/history and complete native sample; pose/calculated changes reject. No parameters/selection/model.update/render calls. Exact-host/rendering-configuration admission remains authoritative.",
                "inputSchema",
                input,
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

    private static Map<String, Object> rows() {
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(row(
                "observation",
                Map.of(
                        "authoringSnapshotId",
                        text(),
                        "sampledAt",
                        text(),
                        "passEntryTimeMillis",
                        Map.of("type", "integer", "minimum", -1),
                        "calculatedFormPresent",
                        Map.of("type", "boolean"),
                        "liveConsumedValuesMatch",
                        Map.of("type", "boolean"))));
        rows.add(row(
                "calculated_form",
                Map.of(
                        "drawOrder",
                        Map.of("type", "integer"),
                        "coordinateType",
                        text(),
                        "interpolatedIdentity",
                        Map.of("type", "boolean"),
                        "pointCount",
                        integer())));
        rows.add(row(
                "calculated_position",
                Map.of("index", integer(), "x", Map.of("type", "number"), "y", Map.of("type", "number"))));
        for (String kind : List.of("live", "consumed")) {
            rows.add(row(
                    kind + "_state",
                    Map.of(
                            "updateVersion",
                            Map.of("type", "integer", "minimum", Integer.MIN_VALUE, "maximum", Integer.MAX_VALUE),
                            "parameterCount",
                            Map.of("type", "integer", "minimum", 0, "maximum", 2048))));
            rows.add(row(
                    kind + "_parameter",
                    Map.of("parameterGuid", text(), "parameterId", text(), "value", Map.of("type", "number"))));
        }
        return Map.of("oneOf", rows);
    }

    private static Map<String, Object> row(String kind, Map<String, Object> fields) {
        var all = new LinkedHashMap<>(fields);
        all.put("kind", Map.of("const", kind));
        return object(all);
    }

    private static Map<String, Object> text() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096);
    }

    private static Map<String, Object> integer() {
        return Map.of("type", "integer", "minimum", 0);
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
}
