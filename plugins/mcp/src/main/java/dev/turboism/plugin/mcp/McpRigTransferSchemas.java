package dev.turboism.plugin.mcp;

import java.util.*;

final class McpRigTransferSchemas {
    static Map<String, Object> definition() {
        var variants = new ArrayList<Object>();
        for (String kind : List.of("art_mesh", "warp_deformer", "rotation_deformer")) {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("documentId", text(4096));
            fields.put("modelId", text(4096));
            fields.put("operationId", text(128));
            fields.put("kind", Map.of("const", kind));
            fields.put("policy", Map.of("const", "REPLACE_FROM_SOURCE_DEFAULT_DELTAS"));
            for (String ref : List.of("source", "target"))
                fields.put(ref, object(Map.of("objectId", text(4096), "snapshotId", text(128))));
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
                                    "sourceParameterGuid",
                                    text(4096),
                                    "targetParameterGuid",
                                    text(4096),
                                    "keys",
                                    Map.of(
                                            "type",
                                            "array",
                                            "minItems",
                                            2,
                                            "maxItems",
                                            65,
                                            "items",
                                            object(Map.of("sourceValue", number(), "targetValue", number())))))));
            fields.put(
                    "deltaTransform",
                    object(Map.of("xx", coefficient(), "xy", coefficient(), "yx", coefficient(), "yy", coefficient())));
            if (kind.equals("rotation_deformer")) {
                fields.put("angleGain", Map.of("type", "number", "minimum", -1000, "maximum", 1000));
                fields.put("reflectionPolicy", Map.of("const", "RELATIVE_XOR"));
            } else
                fields.put(
                        "sourcePointIndices",
                        Map.of(
                                "type",
                                "array",
                                "minItems",
                                1,
                                "maxItems",
                                4096,
                                "items",
                                Map.of("type", "integer", "minimum", 0, "maximum", 4095)));
            variants.add(object(fields));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("ok", Map.of("const", true));
        result.put("applied", Map.of("const", false));
        var state = new LinkedHashMap<String, Object>();
        for (String key : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            state.put(key, Map.of("type", "string"));
        for (String key : List.of("historyGeneration", "historyRevision"))
            state.put(key, Map.of("type", "integer", "minimum", 0));
        result.put("state", object(state));
        result.put("writeTool", Map.of("const", McpKeyformWriteDomain.WRITE));
        result.put("writeRequest", McpKeyformWriteSchemas.input());
        result.put("operationIdReserved", Map.of("const", false));
        result.put("writeAdmission", Map.of("const", "NOT_CHECKED"));
        result.put("sourceReadScope", Map.of("const", "VERIFIED_AT_PLAN_ONLY"));
        result.put(
                "source",
                object(Map.of(
                        "objectId",
                        text(4096),
                        "snapshotId",
                        text(128),
                        "sourceGuid",
                        text(4096),
                        "defaultFormGuid",
                        text(4096))));
        result.put("target", result.get("source"));
        result.put(
                "statistics",
                object(Map.of(
                        "sourceCells",
                        integer(2, 65),
                        "targetCells",
                        integer(2, 65),
                        "plannedForms",
                        integer(1, 64),
                        "plannedPoints",
                        integer(1, 4096))));
        result.put("limitations", Map.of("type", "array", "maxItems", 16, "items", text(256)));
        return Map.of(
                "name",
                McpRigTransferDomain.PLAN,
                "title",
                "Plan explicit default-relative rig geometry transfer",
                "description",
                "Read-only migration of existing ordinary stored geometry between distinct objects of the same kind. Explicit same-session source/target authoring snapshots must share active document/model/history. Map every source GUID to a unique target GUID and every actual declared key one-to-one, including exact default to default; no nearest key, display-ID matching, new bindings or forms. Complete Cartesian1..3-axis grids only,2..65 keys per axis,at most65 cells/forms per object; ArtMesh Morph snapshots rejected, Warp/Rotation snapshots do not describe Morph combinations. Orphan forms rejected. Source shared ordinary forms may be read, target forms must pass ordinary writer default/shared protection. For Mesh/Warp sourcePointIndices[targetIndex]=sourceIndex must be a full permutation with equal point counts. Target points=targetDefault+deltaTransform*(mapped sourceForm-sourceDefault). Explicit invertible2x2 matrix coefficients limited to +/-1e6; no coordinate/parent/pixel conversion or inferred vertex/UV/topology correspondence. Mesh drawOrder/opacity remain target values. Rotation copies raw native angle delta times angleGain, transforms origin delta, multiplies targetDefault scale by sourceForm/sourceDefault scale, and uses explicit RELATIVE_XOR reflection bits. This is component-value transfer, not affine/rotation composition; angle gain is independent of matrix determinant. Preserve target default/grid/baseAngle/source/topology/UV/bindings. Replaces ALL mapped nondefault target stored geometry/components; existing target rig content is not merged. Both snapshots/parents/history rechecked before and after planning; sourceReadScope=VERIFIED_AT_PLAN_ONLY: later source changes do not invalidate the explicit generated values, and the write request guards only target. Max4096 points/form,262144 combined retained point samples,4096 planned samples (Rotation counts one form/origin sample),768KiB request,240KiB output. Native allocation precedes checks. Returns ordinary keyforms.write request for separate preview/CAS/authorization; no operation ID reservation, write/transaction/pose/selection/render or semantic/visual acceptance. Source indices and axis map are explicit user/Agent decisions; must inspect geometry and rendered poses.",
                "inputSchema",
                Map.of("oneOf", variants),
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(
                                object(result),
                                object(Map.of(
                                        "ok",
                                        Map.of("const", false),
                                        "error",
                                        object(Map.of("code", text(128), "message", text(1024))))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    private static Map<String, Object> coefficient() {
        return Map.of("type", "number", "minimum", -1e6, "maximum", 1e6);
    }

    private static Map<String, Object> number() {
        return Map.of("type", "number");
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
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
