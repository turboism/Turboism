package dev.turboism.plugin.mcp;

import java.util.*;

final class McpWarpRigSchemas {
    static Map<String, Object> definition() {
        var fields = new LinkedHashMap<String, Object>();
        for (String field : List.of("documentId", "modelId", "objectId")) fields.put(field, string(4096));
        for (String field : List.of("snapshotId", "operationId")) fields.put(field, string(128));
        var anchor = object(Map.of(
                "controlPointIndex",
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
        fields.put("policy", Map.of("const", "REPLACE_CONTROLS_FROM_DEFAULT"));
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

        var volumeFields = new LinkedHashMap<String, Object>();
        for (String field : List.of("documentId", "modelId", "objectId", "snapshotId", "operationId"))
            volumeFields.put(field, fields.get(field));
        volumeFields.put("policy", Map.of("const", McpWarpHeadVolumePlan.POLICY));
        volumeFields.put(
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
                                "component",
                                Map.of("enum", List.of("YAW", "PITCH", "ROLL")),
                                "degreesPerParameterUnit",
                                Map.of("type", "number")))));
        volumeFields.put(
                "headVolume",
                object(Map.of(
                        "centerX",
                        Map.of("type", "number"),
                        "centerY",
                        Map.of("type", "number"),
                        "radiusX",
                        Map.of("type", "number", "exclusiveMinimum", 0),
                        "radiusY",
                        Map.of("type", "number", "exclusiveMinimum", 0),
                        "depth",
                        Map.of("type", "number", "minimum", 0),
                        "cameraDistance",
                        Map.of("type", "number", "exclusiveMinimum", 0))));
        var volume = object(volumeFields);
        var union = new LinkedHashMap<>(fields);
        union.put("policy", Map.of("enum", List.of("REPLACE_CONTROLS_FROM_DEFAULT", McpWarpHeadVolumePlan.POLICY)));
        union.put("axes", Map.of("oneOf", List.of(fields.get("axes"), volumeFields.get("axes"))));
        union.put("headVolume", volumeFields.get("headVolume"));
        var combinedInput = new LinkedHashMap<>(object(union));
        combinedInput.put(
                "required",
                List.of("documentId", "modelId", "objectId", "snapshotId", "operationId", "axes", "policy"));
        combinedInput.put("oneOf", List.of(input, volume));
        var success = object(Map.of(
                "ok",
                Map.of("const", true),
                "applied",
                Map.of("const", false),
                "policy",
                Map.of("enum", List.of("REPLACE_CONTROLS_FROM_DEFAULT", McpWarpHeadVolumePlan.POLICY)),
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
                McpWarpRigDomain.PLAN,
                "title",
                "Preview stored Warp rig templates",
                "description",
                "Read-only plan from an issued warps.read snapshot. Explicit GUIDs must cover its one to three ordinary axes. Each axis needs unique declared keys on both sides of an exact default and a complete independent Cartesian grid (max 65 cells). Negative/positive endpoint landmark displacements use native stored coordinates, row-major controlPointIndex, 1..16 unique noncoincident anchors per side. Inverse-square-distance interpolation reproduces exact anchors; other controls get normalized weighted displacements. Each key interpolates default-to-declared-extreme displacement; Axis fields add. Optional cornerCorrections declare two or three distinct bound axis GUIDs with NEGATIVE/POSITIVE sides and residual-displacement anchors, not absolute positions. At most 20 unique pair/triple side maps (12 pair quadrants + 8 triple octants for three axes). Weights multiply only the declared axes' nonnegative normalized magnitudes on their specified sides, zero when any declared axis is default or on its opposite side; endpoint-corner weight is one. Undeclared axes do not affect that residual: pair corrections extend across all third-axis keys; triple corrections vanish on any default plane. Corrections use the same landmark interpolation and do not alter default/single-axis forms. These are stored key plans, not proof of evaluated interpolation or visual quality. REPLACE_CONTROLS_FROM_DEFAULT replaces target controls, preserving default/grid/quadTransform/axes/cells. Max 64 edits, 4096 planned points, 240KiB output. Not perspective/anatomy inference, foldover/art/visual acceptance. Returns keyforms.write request for separate admission/CAS. Does not write, move pose/selection, render or start a transaction. Alternatively PROJECT_HEAD_VOLUME_FROM_DEFAULT uses mutually exclusive axes {parameterGuid,component:YAW/PITCH/ROLL,degreesPerParameterUnit} and headVolume {centerX,centerY,radiusX,radiusY,depth,cameraDistance}; cornerCorrections/landmark fields are forbidden. Map every actual ordinary GUID exactly once to a unique component. In native stored coordinates let u=(x-centerX)/radiusX, v=(y-centerY)/radiusY, z=depth*sqrt(max(0,1-u*u-v*v)), with z=0 outside the ellipse. Lift default points along camera rays using X=(x-centerX)*(d-z)/d and Y similarly, then rotate about center/z=0 in mathematical +Y yaw, +X pitch, +Z roll order and project centerX+d*X'/(d-Z'), likewise Y. Angles are (key-default)*gain in degrees; no native angle-unit inference. Positive z faces the camera. Preserve zero-angle points exactly. Reject nonpositive radii, negative depth, camera<=depth or camera-depth below 1% of cameraDistance, per-cell yaw/pitch beyond +/-85 degrees, roll beyond +/-180, near-camera denominators below 1% of d, nonfinite/native float overflow. This is an explicit image-defined depth-field approximation on Warp controls, not 3D reconstruction, occlusion correction or child surface evaluation. Caller must verify parent coordinate space, profile/axis mapping, proposed Warp geometry and real rendered poses. Returns cornerCorrectionCount=0 in volume mode; all normal grid/budget/CAS protections remain.",
                "inputSchema",
                combinedInput,
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
