package dev.turboism.plugin.mcp;

import java.util.*;

/** Exact schemas for the bounded pose inspection workflow and its retained progress/results. */
final class McpPoseGridSchemas {
    static Map<String, Object> definition(String name) {
        boolean scan = name.equals(McpPoseGridDomain.SCAN),
                cancel = name.equals(McpPoseGridDomain.CANCEL),
                release = name.equals(McpPoseGridDomain.RELEASE);
        return Map.of(
                "name",
                name,
                "title",
                scan
                        ? "Render pose grid with mandatory restoration"
                        : cancel ? "Request pose scan cancellation" : "Read pose scan progress/result",
                "description",
                scan
                        ? "Temporarily set unique-ID native parameters in one SDK transaction, render each explicit pose and always restore original values. Partial poses are relative to the original baseline. Optional pixel crop is fixed across frames, bounds checked before pose writes, and retains native raster/crop coordinates per frame without resizing; total pixel budget counts full native rasters, not cropped output. Requires model write permission; may record one Undo entry even after restoration. Max 9 poses/6 axes/1048576 total pixels/3MiB total PNG. Return one typed image per completed frame with state and attachment index. timeBudgetMs <=30000 is cooperative between host calls; cannot interrupt GPU. Query status/result after transport timeout; never blindly replay. Retains at most 2 image-bearing scans and 64 operation identities per server process, isolated by MCP session. Release completed images after saving evidence to allow another scan; released operations cannot be replayed. Duplicate parameter IDs are unavailable until a native GUID write route exists. No save or generic execution."
                        : release
                                ? "Release completed scan images after evidence has been consumed; retain metadata and operation identity to prevent replay. Released results have null imageContentIndex and no image blocks. Running scans cannot be released."
                                : cancel
                                        ? "Request cooperative cancellation at the next scan boundary; current native call must return and parameters must be restored. Cancellation acknowledgment is not rollback or completion. Does not run in the UI queue."
                                        : "Read retained pose scan data in this MCP session/server without touching the model or entering the UI queue. status returns no images; result includes completed frame images, even on partial failure. Missing result means unknown, never proof of unchanged host state.",
                "inputSchema",
                scan ? input() : object(Map.of("operationId", text(128))),
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(
                                error(),
                                name.equals(McpPoseGridDomain.STATUS) || cancel || release ? status() : completed())),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        !scan && !cancel && !release,
                        "destructiveHint",
                        false,
                        "idempotentHint",
                        true,
                        "openWorldHint",
                        false));
    }

    private static Map<String, Object> input() {
        var base = object(Map.of(
                "operationId",
                text(128),
                "expectedState",
                McpRenderDomain.bindingSchema(),
                "width",
                integer(1, 1024),
                "height",
                integer(1, 1024),
                "poses",
                array(pose(), 1, 9),
                "timeBudgetMs",
                integer(1, 30000),
                "restorePolicy",
                Map.of("const", "always")));
        @SuppressWarnings("unchecked")
        var fields = new LinkedHashMap<>((Map<String, Object>) base.get("properties"));
        fields.put("crop", McpRenderDomain.cropSchema());
        var input = new LinkedHashMap<>(base);
        input.put("properties", fields);
        return input;
    }

    private static Map<String, Object> pose() {
        return object(Map.of(
                "label",
                text(64),
                "values",
                array(object(Map.of("parameterId", text(4096), "value", Map.of("type", "number"))), 1, 6)));
    }

    private static Map<String, Object> parameter() {
        return object(Map.of("ordinal", integer(0, 2048), "id", text(4096), "value", Map.of("type", "number")));
    }

    private static Map<String, Object> completed() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("ok", Map.of("type", "boolean"));
        fields.put("operationId", text(128));
        fields.put(
                "outcome",
                Map.of(
                        "enum",
                        List.of(
                                "RUNNING",
                                "COMPLETED",
                                "FAILED",
                                "CANCELLED",
                                "TIME_BUDGET_EXCEEDED",
                                "RESTORE_UNVERIFIED",
                                "NOT_APPLIED",
                                "OUTCOME_UNKNOWN")));
        fields.put("initialState", McpRenderDomain.bindingSchema());
        fields.put("initialParameters", array(parameter(), 0, 2048));
        fields.put("requestedPoses", integer(1, 9));
        fields.put("poses", array(pose(), 1, 9));
        fields.put("parametersRestored", Map.of("type", "boolean"));
        fields.put("imagesReleased", Map.of("type", "boolean"));
        fields.put("failureCode", nullableString());
        fields.put("failedPoseIndex", Map.of("type", List.of("integer", "null")));
        fields.put("transactionOutcome", nullableString());
        fields.put("diagnosticId", nullableString());
        fields.put(
                "receipt",
                Map.of(
                        "oneOf",
                        List.of(
                                Map.of("type", "null"),
                                object(Map.of(
                                        "transactionId",
                                        text(128),
                                        "historyEntryId",
                                        nullableString(),
                                        "generationBefore",
                                        Map.of("type", "integer"),
                                        "revisionBefore",
                                        Map.of("type", "integer"),
                                        "revisionAfter",
                                        Map.of("type", "integer"))))));
        fields.put("frames", array(frame(), 0, 9));
        return object(fields);
    }

    static Map<String, Object> frame() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("index", integer(0, 8));
        fields.put("label", text(64));
        fields.put("imageContentIndex", Map.of("type", List.of("integer", "null")));
        fields.put("captureId", text(128));
        fields.put("capturedAt", text(128));
        fields.put("state", McpRenderDomain.bindingSchema());
        fields.put("parameters", array(object(Map.of("id", text(4096), "value", Map.of("type", "number"))), 1, 6));
        fields.put("width", integer(1, 1024));
        fields.put("height", integer(1, 1024));
        fields.put("renderWidth", integer(1, 1024));
        fields.put("renderHeight", integer(1, 1024));
        fields.put("crop", McpRenderDomain.cropSchema());
        fields.put("pixelCoordinates", text(128));
        fields.put("modelToPixelTransform", Map.of("type", "null"));
        fields.put("sha256", text(64));
        fields.put("byteLength", integer(1, 3 * 1024 * 1024));
        return object(fields);
    }

    private static Map<String, Object> status() {
        return object(Map.of(
                "ok",
                Map.of("const", true),
                "operationId",
                text(128),
                "phase",
                text(64),
                "terminal",
                Map.of("type", "boolean"),
                "completedPoses",
                integer(0, 9),
                "requestedPoses",
                integer(1, 9),
                "cancelRequested",
                Map.of("type", "boolean"),
                "imagesReleased",
                Map.of("type", "boolean"),
                "outcome",
                text(64)));
    }

    private static Map<String, Object> error() {
        return object(Map.of(
                "ok",
                Map.of("const", false),
                "operationId",
                Map.of("type", "string"),
                "outcome",
                Map.of("enum", List.of("NOT_APPLIED", "OUTCOME_UNKNOWN")),
                "error",
                object(Map.of("code", text(64), "message", text(4096)))));
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

    private static Map<String, Object> array(Map<String, Object> items, int min, int max) {
        return Map.of("type", "array", "items", items, "minItems", min, "maxItems", max);
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> nullableString() {
        return Map.of("type", List.of("string", "null"));
    }
}
