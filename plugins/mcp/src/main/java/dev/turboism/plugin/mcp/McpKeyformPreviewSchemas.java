package dev.turboism.plugin.mcp;

import java.util.*;

final class McpKeyformPreviewSchemas {
    static Map<String, Object> definition() {
        var target = new LinkedHashMap<String, Object>();
        target.put("kind", Map.of("enum", List.of("art_mesh", "warp_deformer", "rotation_deformer")));
        for (String field : List.of("objectId", "sourceGuid", "snapshotId", "beforeHash", "expectedAfterHash"))
            target.put(field, text(4096));
        target.put("readbackVerified", Map.of("const", false));
        for (String field : List.of(
                "requestedForms",
                "changedForms",
                "budgetedPoints",
                "changedPoints",
                "changedDrawOrders",
                "changedOpacities",
                "changedReflectionFlags"))
            target.put(field, Map.of("type", "integer", "minimum", 0, "maximum", McpKeyformWritePlan.MAX_POINTS));
        for (String field : List.of("maxPointDisplacement", "maxAngleDelta", "maxOriginDisplacement", "maxScaleDelta"))
            target.put(field, Map.of("type", "number", "minimum", 0));
        target.put(
                "forms",
                array(
                        object(Map.of(
                                "formGuid",
                                text(4096),
                                "changedFields",
                                array(
                                        Map.of(
                                                "enum",
                                                List.of(
                                                        "positions",
                                                        "controlPoints",
                                                        "drawOrder",
                                                        "opacity",
                                                        "angle",
                                                        "origin",
                                                        "scale",
                                                        "reflectedX",
                                                        "reflectedY")),
                                        0,
                                        9))),
                        1,
                        64));
        var success = new LinkedHashMap<String, Object>();
        success.put("ok", Map.of("const", true));
        success.put("applied", Map.of("const", false));
        success.put("operationId", text(128));
        success.put("requestHash", text(64));
        success.put(
                "state",
                object(Map.of(
                        "documentId",
                        text(4096),
                        "modelId",
                        text(4096),
                        "historyGeneration",
                        Map.of("type", "integer", "minimum", 0),
                        "historyRevision",
                        Map.of("type", "integer", "minimum", 0),
                        "historyDocumentBinding",
                        text(4096),
                        "historyManagerBinding",
                        text(4096))));
        success.put("requestedForms", Map.of("type", "integer", "minimum", 1, "maximum", 64));
        success.put(
                "budgetedPoints", Map.of("type", "integer", "minimum", 0, "maximum", McpKeyformWritePlan.MAX_POINTS));
        success.put("targets", array(object(target), 1, 8));
        success.put("writeAdmission", Map.of("const", "NOT_CHECKED"));
        success.put("reservedByPreview", Map.of("const", false));
        return Map.of(
                "name",
                McpKeyformWriteDomain.PREVIEW,
                "title",
                "Preview a typed ordinary keyform batch",
                "description",
                "Read-only preflight of the exact keyforms.write request across ArtMesh/Warp/Rotation. Reuses session snapshots, full stored-state/parent/history CAS, ordinary/default/shared/Morph protections and aggregate input/form/point budgets. Verifies all targets before/after computing bounded native-space diffs and hashes. No setter, parameter/selection movement, render, transaction resolution, operation reservation or snapshot invalidation. Rejects an already admitted operationId: query keyforms.result instead. Returns per-object/form changedFields, exact changed point/order/opacity/reflection counts and maximum native point/origin displacement, absolute native angle delta (not wrapped degrees) and scale delta. No cross-parent/model-space distance, geometry/visual QA or live writer/permission/Undo admission is claimed. readbackVerified=false describes predicted state, never a write receipt. requestHash fingerprints the exact submitted request; preview cannot grant write authorization or prevent a later stale state. Write performs fresh checks. Max 8 targets/64 forms/16384 budgeted points/768KiB input/240KiB preview; opacity batches count retained positions. No-op forms remain visible in requestedForms with empty changedFields.",
                "inputSchema",
                McpKeyformWriteSchemas.input(),
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(object(success), object(Map.of("ok", Map.of("const", false), "error", text(128))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> array(Object item, int min, int max) {
        return Map.of("type", "array", "minItems", min, "maxItems", max, "items", item);
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
