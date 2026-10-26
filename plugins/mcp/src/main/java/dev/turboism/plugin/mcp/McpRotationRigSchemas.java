package dev.turboism.plugin.mcp;

import java.util.*;

final class McpRotationRigSchemas {
    static Map<String, Object> definition() {
        var fields = new LinkedHashMap<String, Object>();
        for (String field : List.of("documentId", "modelId", "objectId")) fields.put(field, string(4096));
        for (String field : List.of("snapshotId", "operationId")) fields.put(field, string(128));

        fields.put("policy", Map.of("const", "REPLACE_ANGLE_FROM_DEFAULT"));
        var legacy = new LinkedHashMap<>(fields);
        legacy.put("parameterGuid", string(4096));
        legacy.put("nativeAnglePerParameterUnit", Map.of("type", "number"));
        var multi = new LinkedHashMap<>(fields);
        multi.put(
                "axes",
                Map.of(
                        "type",
                        "array",
                        "minItems",
                        1,
                        "maxItems",
                        3,
                        "items",
                        object(Map.of(
                                "parameterGuid",
                                string(4096),
                                "nativeAnglePerParameterUnit",
                                Map.of("type", "number")))));
        var allFields = new LinkedHashMap<>(legacy);
        allFields.put("axes", multi.get("axes"));
        var input = new LinkedHashMap<>(object(allFields));
        input.put("required", List.copyOf(fields.keySet()));
        input.put("oneOf", List.of(object(legacy), object(multi)));
        var success = object(Map.of(
                "ok",
                Map.of("const", true),
                "applied",
                Map.of("const", false),
                "policy",
                Map.of("const", "REPLACE_ANGLE_FROM_DEFAULT"),
                "writeTool",
                Map.of("const", McpKeyformWriteDomain.WRITE),
                "writeRequest",
                McpKeyformWriteSchemas.input(),
                "limitations",
                Map.of("type", "array", "items", string(256), "maxItems", 8)));
        var error = object(Map.of(
                "ok", Map.of("const", false), "error", object(Map.of("code", string(128), "message", string(1024)))));
        return Map.of(
                "name",
                McpRotationRigDomain.PLAN,
                "title",
                "Preview a stored Rotation angle rig",
                "description",
                "Read-only plan from an issued rotations.read snapshot. Use either legacy parameterGuid/nativeAnglePerParameterUnit (single ordinary axis only), or axes with 1..3 explicit GUID/gain entries covering every ordinary axis exactly once. Requires unique in-range keys, an exact default on every axis, a complete independent Cartesian grid of at most 65 cells and at most 64 non-default edits. Each axis must have at least two keys. Replaces each target angle with all-default stored angle + sum((cellKey - axisDefault) * gain), summed in snapshot axis order and rounded once to native float. Preserves each target pivot/scale/reflections, default form, baseAngle, axes/cells and unrelated forms. No native angle-unit conversion, rotation composition, semantic XYZ/pivot inference or quality acceptance. Returns a closed keyforms.write request for separate approval and fresh CAS. Repeated planning does not accumulate offsets. No setters, pose/selection movement, render or transaction. Max 128KiB output; default/shared form writes remain excluded.",
                "inputSchema",
                input,
                "outputSchema",
                Map.of("oneOf", List.of(success, error)),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    private static Map<String, Object> string(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
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
