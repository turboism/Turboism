package dev.turboism.plugin.mcp;

import java.util.*;

/** Closed ordinary-only contracts; no SDK invoke, file paths, UI selection or implicit pose edits. */
final class McpBindingInitializationSchemas {
    static Map<String, Object> definition(String name) {
        boolean plan = name.equals(McpBindingInitializationDomain.PLAN),
                result = name.equals(McpBindingInitializationDomain.RESULT);
        var input = new LinkedHashMap<String, Object>();
        if (!result) for (String field : List.of("documentId", "modelId")) input.put(field, text(4096));
        if (plan) {
            input.put("objectId", text(4096));
            input.put("kind", Map.of("enum", List.of("art_mesh", "warp_deformer", "rotation_deformer")));
            input.put("authoringSnapshotId", text(128));
            input.put(
                    "axes",
                    array(
                            object(Map.of(
                                    "parameterId",
                                    text(4096),
                                    "parameterGuid",
                                    text(128),
                                    "keys",
                                    array(Map.of("type", "number"), 3, 9))),
                            1,
                            3));
        } else {
            input.put("operationId", text(128));
            if (!result) input.put("planId", text(128));
        }
        var axis = object(Map.of(
                "parameterId",
                text(4096),
                "parameterGuid",
                text(128),
                "minimum",
                Map.of("type", "number"),
                "defaultValue",
                Map.of("type", "number"),
                "maximum",
                Map.of("type", "number"),
                "keys",
                array(Map.of("type", "number"), 3, 9)));
        var target = object(Map.of(
                "objectId",
                text(4096),
                "kind",
                Map.of("enum", List.of("art_mesh", "warp_deformer", "rotation_deformer")),
                "sourceGuid",
                text(4096),
                "axes",
                array(axis, 1, 3)));
        if (!plan && !result) input.put("target", target);
        Map<String, Object> output;
        if (plan) {
            var state = object(Map.of(
                    "documentId",
                    text(4096),
                    "modelId",
                    text(4096),
                    "historyGeneration",
                    integer(0, Long.MAX_VALUE),
                    "historyRevision",
                    integer(0, Long.MAX_VALUE),
                    "historyDocumentBinding",
                    Map.of("type", "string"),
                    "historyManagerBinding",
                    Map.of("type", "string")));
            output = Map.of(
                    "oneOf",
                    List.of(
                            object(Map.of(
                                    "ok",
                                    Map.of("const", true),
                                    "planId",
                                    text(128),
                                    "objectId",
                                    text(4096),
                                    "sourceGuid",
                                    text(4096),
                                    "axes",
                                    array(axis, 1, 3),
                                    "cellCount",
                                    integer(3, 27),
                                    "storedSamples",
                                    integer(1, 32768),
                                    "state",
                                    state,
                                    "hostWriteAdmission",
                                    Map.of("const", "UNPROVEN"),
                                    "target",
                                    target)),
                            object(Map.of("ok", Map.of("const", false), "error", text(128)))));
        } else {
            @SuppressWarnings("unchecked")
            var fields = new LinkedHashMap<>(
                    (Map<String, Object>) McpWriteResults.schema().get("properties"));
            var cell = object(Map.of(
                    "formGuid",
                    text(128),
                    "defaultCell",
                    Map.of("type", "boolean"),
                    "coordinates",
                    array(object(Map.of("parameterGuid", text(128), "value", Map.of("type", "number"))), 1, 3)));
            fields.put("cells", array(cell, 0, 27));
            fields.put("renderVerified", Map.of("const", false));
            fields.put("rollbackStateVerified", Map.of("type", "boolean"));
            output = object(fields);
        }
        return Map.of(
                "name",
                name,
                "title",
                plan
                        ? "Plan an ordinary binding grid"
                        : result
                                ? "Read retained binding initialization result"
                                : "Initialize an ordinary binding grid",
                "description",
                plan
                        ? "Read-only plan for one zero-axis ArtMesh/Warp/Rotation with one stored cell/form. Requires a live issued authoring snapshot, exact source/parent/lock/edit-level/history CAS and full parameter GUID/definition/current-value inventory (max512,240KiB); duplicate display IDs/GUIDs rejected. 1..3 ordinary nonrepeat/noncombined axes, at default values, 3..9 distinct keys each including exact default and keys on both sides within range; max27 Cartesian cells,4096 points,32768 samples,4MiB initial retained state. 16 plans/16MiB total/5min TTL with no extension of source snapshot TTL. No pose/selection change or write-admission probe. Runtime hierarchy/extension/exact-host/Undo admission remains authoritative."
                        : result
                                ? "Session-local retained result, zero host reads. Missing means OUTCOME_UNKNOWN, not no change. Max128 admitted operations, no eviction/restart recovery. Timeout/Abort does not cancel host work; reconcile before any retry."
                                : "Echo plan.target unchanged in initialize input so the permission UI shows actual source/object/parameter/key scope; a changed target is rejected. Create one previously unbound ordinary grid with SDK bindValues in one owned transaction. Verify complete source, parameters and metadata before/inside/after; after each axis require exactly the full Cartesian cells, distinct independent forms, unchanged source/grid/baseAngle and every form's positions/draw-order/nullable-opacity or full Rotation form identical to the original zero-axis form. The default cell is located by exact full tuple; original form GUID placement is not assumed. Shared/missing/extra cells/forms, Morph, changed default content, moved parameter or budget failure throws inside transaction. Native allocations precede MCP readback limits. Return actual form GUID tuples only on successful final readback; source snapshot invalidated on success. Re-read authoring snapshot for rig writers; returned cells are not a writable snapshot. Readback after rollback checks declared target/parameter state; no real-host/GPU/visual/persistence guarantee. Results retain observed hash and receipt; warnings/unknown are not retry-safe. Not transactionEligible and no arbitrary target batching or legacy apply composition.",
                "inputSchema",
                object(input),
                "outputSchema",
                output,
                "annotations",
                Map.of(
                        "readOnlyHint",
                        plan || result,
                        "destructiveHint",
                        false,
                        "idempotentHint",
                        true,
                        "openWorldHint",
                        false));
    }

    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> integer(long min, long max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> array(Object items, int min, int max) {
        return Map.of("type", "array", "minItems", min, "maxItems", max, "items", items);
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
