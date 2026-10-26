package dev.turboism.plugin.mcp;

import java.util.*;

final class McpMeshRigSchemas {
    static Map<String, Object> definition() {
        var fields = new LinkedHashMap<String, Object>();
        for (String field : List.of("documentId", "modelId", "objectId")) fields.put(field, string(4096));
        for (String field : List.of("snapshotId", "operationId")) fields.put(field, string(128));
        var anchor = object(Map.of(
                "vertexIndex",
                Map.of("type", "integer", "minimum", 0, "maximum", 4095),
                "dx",
                Map.of("type", "number"),
                "dy",
                Map.of("type", "number")));
        var anchors = Map.of("type", "array", "minItems", 1, "maxItems", 16, "items", anchor);
        fields.put(
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
                                "negativeAnchors",
                                anchors,
                                "positiveAnchors",
                                anchors))));
        fields.put("policy", Map.of("const", "REPLACE_POSITIONS_FROM_DEFAULT"));
        var direction =
                object(Map.of("parameterGuid", string(4096), "side", Map.of("enum", List.of("NEGATIVE", "POSITIVE"))));
        fields.put(
                "cornerCorrections",
                Map.of(
                        "type",
                        "array",
                        "minItems",
                        0,
                        "maxItems",
                        20,
                        "items",
                        object(Map.of(
                                "directions",
                                Map.of("type", "array", "minItems", 2, "maxItems", 3, "items", direction),
                                "anchors",
                                anchors))));
        var input = new LinkedHashMap<>(object(fields));
        input.put(
                "required",
                fields.keySet().stream()
                        .filter(k -> !k.equals("cornerCorrections"))
                        .toList());

        var success = object(Map.of(
                "ok",
                Map.of("const", true),
                "applied",
                Map.of("const", false),
                "policy",
                Map.of("const", "REPLACE_POSITIONS_FROM_DEFAULT"),
                "cornerCorrectionCount",
                Map.of("type", "integer", "minimum", 0, "maximum", 20),
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
                McpMeshRigDomain.PLAN,
                "title",
                "Preview landmark-driven stored ArtMesh rig",
                "description",
                "Read-only plan from an issued meshes.read snapshot. Explicit GUIDs must cover its one to three ordinary axes. Each axis needs unique declared keys on both sides of an exact default and a complete independent Cartesian grid (max 65 cells). Negative/positive endpoint landmark displacements use native stored coordinates, source topology vertexIndex, 1..16 unique noncoincident anchors per side. Inverse-square-distance interpolation reproduces exact anchors; other vertices get normalized weighted displacements. Each key interpolates default-to-declared-extreme displacement; Axis fields add. Optional cornerCorrections declare two or three distinct bound axis GUIDs with NEGATIVE/POSITIVE sides and residual-displacement anchors, not absolute positions. At most 20 unique pair/triple side maps (12 pair quadrants + 8 triple octants for three axes). Weights multiply only the declared axes' nonnegative normalized magnitudes on their specified sides, zero when any declared axis is default or on its opposite side; endpoint-corner weight is one. Undeclared axes do not affect that residual: pair corrections extend across all third-axis keys; triple corrections vanish on any default plane. Corrections use the same landmark interpolation and do not alter default/single-axis forms. These are stored key plans, not proof of evaluated interpolation or visual quality. REPLACE_POSITIONS_FROM_DEFAULT replaces target positions, preserving source positions/UV/indices, stored draw orders, default and axes/cells. Max 64 edits, 4096 planned points, 240KiB output. Not perspective/anatomy inference, foldover/art/visual acceptance. Returns keyforms.write request for separate admission/CAS. Does not write, move pose/selection, render or start a transaction.",
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
