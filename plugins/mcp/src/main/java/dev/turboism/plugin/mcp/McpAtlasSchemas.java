package dev.turboism.plugin.mcp;

import java.util.*;

/** Closed wire contracts; native atlas targets never leave the server. */
final class McpAtlasSchemas {
    static Map<String, Object> definition(String name) {
        boolean read = name.equals(McpAtlasDomain.READ),
                plan = name.equals(McpAtlasDomain.PLAN),
                apply = name.equals(McpAtlasDomain.APPLY),
                cancel = name.equals(McpAtlasDomain.CANCEL);
        var input = new LinkedHashMap<String, Object>();
        if (read || plan || apply) {
            input.put("documentId", text(4096));
            input.put("modelId", text(4096));
        }
        if (read) input.put("maxTotalPixels", integer(1, McpAtlasLayout.MAX_PIXELS));
        else {
            input.put("operationId", text(128));
            if (plan || apply) input.put("snapshotId", text(128));
        }
        if (plan) input.put("policy", Map.of("const", "SHELF_HEIGHT_DESCENDING"));
        if (apply) input.put("layout", layout(true));
        Map<String, Object> success;
        if (read) {
            var item = object(Map.of(
                    "textureId",
                    text(4096),
                    "width",
                    integer(1, 8192),
                    "height",
                    integer(1, 8192),
                    "outlineHash",
                    nullable(text(64)),
                    "outlineVertexCount",
                    integer(0, 8192)));
            var constraints = object(Map.of(
                    "pageWidth",
                    integer(1, 8192),
                    "pageHeight",
                    integer(1, 8192),
                    "edgeMargin",
                    integer(0, Integer.MAX_VALUE),
                    "itemPadding",
                    integer(0, Integer.MAX_VALUE),
                    "maxPages",
                    integer(1, 32)));
            var atlas = object(Map.of(
                    "documentId",
                    text(4096),
                    "modelId",
                    text(4096),
                    "atlasIdHash",
                    text(64),
                    "constraints",
                    constraints,
                    "items",
                    array(item, 0, 512),
                    "currentPlan",
                    layout(false)));
            success = object(Map.of(
                    "ok",
                    Map.of("const", true),
                    "snapshotId",
                    text(128),
                    "snapshotHash",
                    text(64),
                    "state",
                    state(),
                    "atlas",
                    atlas,
                    "maxTotalPixels",
                    integer(1, McpAtlasLayout.MAX_PIXELS),
                    "mode",
                    Map.of("const", "COMPLETE_RECTANGLES"),
                    "outlinesUsedForPacking",
                    Map.of("const", false)));
        } else if (plan) {
            var request = new LinkedHashMap<>(input);
            request.remove("policy");
            request.put("layout", layout(true));
            success = object(Map.of(
                    "ok",
                    Map.of("const", true),
                    "snapshotHash",
                    text(64),
                    "applyRequest",
                    object(request),
                    "pageCount",
                    integer(1, 32),
                    "placementCount",
                    integer(0, 512),
                    "totalPixels",
                    integer(1, McpAtlasLayout.MAX_PIXELS),
                    "writeAdmission",
                    Map.of("const", "NOT_CHECKED"),
                    "optimality",
                    Map.of("const", "NOT_CLAIMED")));
        } else {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("ok", Map.of("type", "boolean"));
            fields.put("operationId", text(128));
            fields.put("phase", Map.of("enum", List.of("QUEUED", "HOST_RUNNING", "TERMINAL")));
            fields.put(
                    "outcome",
                    Map.of(
                            "enum",
                            List.of(
                                    "RUNNING",
                                    "CANCELLED",
                                    "NOT_APPLIED",
                                    "APPLIED",
                                    "NO_CHANGE",
                                    "OUTCOME_UNKNOWN",
                                    "APPLIED_WITH_READBACK_WARNING")));
            fields.put("code", text(128));
            fields.put("startedAt", text(64));
            fields.put("completedAt", nullable(text(64)));
            for (String key : List.of("hostAdmitted", "cancelRequested", "readbackVerified"))
                fields.put(key, Map.of("type", "boolean"));
            for (String key : List.of(
                    "hostCancellationConfirmed",
                    "nativeUndoReceiptAvailable",
                    "textureBindingsVerified",
                    "pixelSamplingVerified")) fields.put(key, Map.of("const", false));
            fields.put("nativeStatus", nullable(Map.of("enum", List.of("APPLIED", "NO_CHANGE"))));
            fields.put(
                    "nativeFailureCode",
                    nullable(Map.of(
                            "enum",
                            List.of(
                                    "PERMISSION_DENIED",
                                    "CAPABILITY_UNAVAILABLE",
                                    "TARGET_STALE",
                                    "PLAN_INVALID",
                                    "PROVIDER_REJECTED",
                                    "PROVIDER_FAILED",
                                    "RUNTIME_CLOSED"))));
            for (String key : List.of("beforeSnapshotHash", "expectedLayoutHash", "actualLayoutHash"))
                fields.put(key, nullable(text(64)));
            fields.put("historyAfter", nullable(state()));
            success = object(fields);
        }
        return Map.of(
                "name",
                name,
                "title",
                read
                        ? "Read complete atlas"
                        : plan
                                ? "Plan complete atlas"
                                : apply
                                        ? "Apply complete atlas job"
                                        : cancel ? "Request atlas cancellation" : "Read atlas job result",
                "description",
                "Bounded complete rectangle atlas workflow. Read retains the opaque native target for five minutes in this MCP session (max16 snapshots), including full native constraints, images/outlines and history CAS; no UI selection, parameters or render changes. Rejects transient native packing invocation targets and polygon plans. Plan uses deterministic height-descending shelves, no rotation/scaling or optimality claim; returns an exact applyRequest without reserving an operation or checking write admission. Apply rechecks full scope/history/native state and complete image coverage, dimensions, margins, padding, page names/sizes and pixel budget. Limits: 512 items, 32 pages, dimensions8192, 8192 outline vertices, 240KiB projection/request, explicit maxTotalPixels<=67108864. Native apply owns standalone atlas Undo, not SDK authoring transaction/rollback; not transactionEligible, no native Undo receipt. One in-flight job and 256 retained identities; poll result. Exact operation replay never rereads/reapplies; conflicting requests reject. Cancel only prevents pre-host admission, never claims host cancellation/rollback after admission. Session close drops access, not admitted host work. Runtime exact-version/permission/target admission remains authoritative. Provider failures/rejections or lost host response conservatively return OUTCOME_UNKNOWN; failed successful-apply readback warns. Semantic layout readback does not prove ArtMesh binding, GPU pixels, Undo, save/reopen or real-host acceptance. No arbitrary paths, targets, code, polygons or append API.",
                "inputSchema",
                object(input),
                "outputSchema",
                Map.of("oneOf", List.of(success, object(Map.of("ok", Map.of("const", false), "error", text(128))))),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        !apply && !cancel,
                        "destructiveHint",
                        false,
                        "idempotentHint",
                        !read,
                        "openWorldHint",
                        false));
    }

    private static Map<String, Object> layout(boolean write) {
        return object(Map.of(
                "pageWidth",
                integer(1, 8192),
                "pageHeight",
                integer(1, 8192),
                "pageCount",
                integer(1, 32),
                "scale",
                Map.of("const", 1),
                "pageNames",
                array(text(128), write ? 1 : 0, 32),
                "pageSizes",
                array(object(Map.of("width", integer(1, 8192), "height", integer(1, 8192))), 1, 32),
                "placements",
                array(
                        object(Map.of(
                                "textureId",
                                text(4096),
                                "pageIndex",
                                integer(0, 31),
                                "x",
                                integer(0, 8192),
                                "y",
                                integer(0, 8192),
                                "width",
                                integer(1, 8192),
                                "height",
                                integer(1, 8192),
                                "rotated",
                                write ? Map.of("const", false) : Map.of("type", "boolean"))),
                        0,
                        512)));
    }

    private static Map<String, Object> state() {
        return object(Map.of(
                "documentId",
                text(4096),
                "modelId",
                text(4096),
                "historyGeneration",
                integer(0, Long.MAX_VALUE),
                "historyRevision",
                integer(0, Long.MAX_VALUE),
                "historyDocumentBinding",
                text(4096),
                "historyManagerBinding",
                text(4096)));
    }

    private static Map<String, Object> nullable(Object schema) {
        return Map.of("oneOf", List.of(schema, Map.of("type", "null")));
    }

    private static Map<String, Object> array(Object schema, int min, int max) {
        return Map.of("type", "array", "items", schema, "minItems", min, "maxItems", max);
    }

    private static Map<String, Object> integer(long min, long max) {
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
