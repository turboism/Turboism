package dev.turboism.plugin.mcp;

import java.util.*;

/** Closed ordinary-write union; never accepts arbitrary SDK method/property names. */
final class McpKeyformWriteSchemas {
    static Map<String, Object> definition(String name, boolean query) {
        return Map.of(
                "name",
                name,
                "title",
                query ? "Read retained keyform operation result" : "Write ordinary stored keyforms",
                "description",
                query
                        ? "Read a historical operation result in the current MCP session without touching the model. Missing result means unknown, not unchanged. Retention lasts until server stops, at most 256 admitted operations; no eviction/replay."
                        : "Edit independent non-default ordinary ArtMesh/Warp/Rotation forms using issued session snapshots. All targets are preflighted against complete stored state and parent identities before one SDK transaction. ArtMesh edits accept positions, integer drawOrder (0..1000), and/or absolute ordinary opacity (0..1, requires observed stored opacity and independent native admission); omitted fields retain expected snapshot values. Combined batches preserve omitted fields; opacity batches include all retained target vertices in the point budget. Max 8 objects/64 forms/16384 points/768KiB. Reuse the same operationId and exact request to retrieve an admitted result; conflicting reuse is rejected. Query keyforms.result after timeout; do not blindly retry. No default/shared/Morph/topology writes, parameter movement, file saving or UI selection. Reread snapshots after commit. This tool owns its transaction and cannot nest in transaction.execute.",
                "inputSchema",
                query ? object(Map.of("operationId", identity(128))) : input(),
                "outputSchema",
                output(),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        query,
                        "destructiveHint",
                        false,
                        "idempotentHint",
                        true,
                        "openWorldHint",
                        false));
    }

    static Map<String, Object> input() {
        var coordinates = array(object(Map.of("parameterGuid", identity(4096), "value", Map.of("type", "number"))), 64);
        var point = object(Map.of("x", Map.of("type", "number"), "y", Map.of("type", "number")));
        var rotation = object(Map.of(
                "angle",
                Map.of("type", "number"),
                "originX",
                Map.of("type", "number"),
                "originY",
                Map.of("type", "number"),
                "scale",
                Map.of("type", "number", "exclusiveMinimum", 0),
                "reflectedX",
                Map.of("type", "boolean"),
                "reflectedY",
                Map.of("type", "boolean")));
        var targets = new ArrayList<Object>();
        for (String kind : List.of("art_mesh", "warp_deformer", "rotation_deformer")) {
            String field =
                    switch (kind) {
                        case "art_mesh" -> "positions";
                        case "warp_deformer" -> "controlPoints";
                        default -> "form";
                    };
            Map<String, Object> edit = object(Map.of(
                    "formGuid",
                    identity(4096),
                    "coordinates",
                    coordinates,
                    field,
                    kind.equals("rotation_deformer") ? rotation : array(point, McpKeyformWritePlan.MAX_POINTS)));
            if (kind.equals("art_mesh")) {
                var alternatives = new ArrayList<Object>();
                var names = List.of("positions", "drawOrder", "opacity");
                var schemas = List.of(
                        array(point, McpKeyformWritePlan.MAX_POINTS),
                        Map.of("type", "integer", "minimum", 0, "maximum", 1000),
                        Map.of("type", "number", "minimum", 0, "maximum", 1));
                for (int mask = 1; mask < 8; mask++) {
                    var fields = new java.util.LinkedHashMap<String, Object>();
                    fields.put("formGuid", identity(4096));
                    fields.put("coordinates", coordinates);
                    for (int bit = 0; bit < 3; bit++)
                        if ((mask & (1 << bit)) != 0) fields.put(names.get(bit), schemas.get(bit));
                    alternatives.add(object(fields));
                }
                edit = Map.of("oneOf", alternatives);
            }
            targets.add(object(Map.of(
                    "kind",
                    Map.of("const", kind),
                    "objectId",
                    identity(4096),
                    "snapshotId",
                    identity(128),
                    "edits",
                    array(edit, McpKeyformWritePlan.MAX_EDITS))));
        }
        return object(Map.of(
                "documentId",
                identity(4096),
                "modelId",
                identity(4096),
                "operationId",
                identity(128),
                "targets",
                array(Map.of("oneOf", targets), McpKeyformWriteDomain.MAX_TARGETS)));
    }

    private static Map<String, Object> output() {
        var history = object(Map.of(
                "availability",
                Map.of("type", "string"),
                "generation",
                Map.of("type", "integer"),
                "revision",
                Map.of("type", "integer"),
                "position",
                Map.of("type", "integer"),
                "documentBindingId",
                Map.of("type", "string"),
                "managerBindingId",
                Map.of("type", "string")));
        var receipt = object(Map.of(
                "transactionId",
                Map.of("type", "string"),
                "label",
                Map.of("type", "string"),
                "historyEntryId",
                nullableString(),
                "historyBefore",
                history,
                "historyAfter",
                history));
        var target = object(Map.of(
                "kind",
                Map.of("enum", List.of("art_mesh", "warp_deformer", "rotation_deformer")),
                "objectId",
                Map.of("type", "string"),
                "sourceGuid",
                Map.of("type", "string"),
                "beforeHash",
                Map.of("type", "string"),
                "expectedAfterHash",
                Map.of("type", "string"),
                "readbackVerified",
                Map.of("type", "boolean")));
        var fields = new LinkedHashMap<String, Object>();
        fields.put("ok", Map.of("type", "boolean"));
        fields.put("operationId", Map.of("type", "string"));
        fields.put(
                "outcome",
                Map.of(
                        "enum",
                        List.of(
                                "APPLIED",
                                "NO_CHANGE",
                                "APPLIED_WITH_READBACK_WARNING",
                                "ROLLED_BACK",
                                "NOT_APPLIED",
                                "OUTCOME_UNKNOWN",
                                "RUNNING")));
        fields.put("code", Map.of("type", "string"));
        fields.put("message", Map.of("type", "string"));
        fields.put("transactionOutcome", nullableString());
        fields.put("diagnosticId", nullableString());
        fields.put("receipt", Map.of("oneOf", List.of(receipt, Map.of("type", "null"))));
        fields.put("targets", Map.of("type", "array", "maxItems", McpKeyformWriteDomain.MAX_TARGETS, "items", target));
        return object(fields);
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

    private static Map<String, Object> array(Map<String, Object> item, int max) {
        return Map.of("type", "array", "minItems", 1, "maxItems", max, "items", item);
    }

    private static Map<String, Object> identity(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> nullableString() {
        return Map.of("type", List.of("string", "null"));
    }
}
