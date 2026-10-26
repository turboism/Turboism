package dev.turboism.plugin.mcp;

import java.util.*;

final class McpMocExportSchemas {
    static Map<String, Object> definition(String name) {
        boolean plan = name.equals(McpMocExportDomain.PLAN),
                execute = name.equals(McpMocExportDomain.EXECUTE),
                cancel = name.equals(McpMocExportDomain.CANCEL);
        var input = new LinkedHashMap<String, Object>();
        input.put("operationId", text(128));
        if (plan || execute) {
            input.put("grantId", text(128));
            input.put("writePolicy", Map.of("const", "REPLACE_AUTHORIZED_FILE"));
        }
        if (plan) {
            input.put("documentId", text(4096));
            input.put("modelId", text(4096));
            input.put("settings", settings());
            input.put(
                    "budgets",
                    object(Map.of(
                            "maxMocBytes",
                            integer(64, McpMocPackage.MAX_MOC),
                            "maxArchiveBytes",
                            integer(1, McpMocPackage.MAX_ARCHIVE),
                            "maxTotalPixels",
                            integer(1, McpMocPackage.MAX_PIXELS))));
        }
        if (execute) input.put("snapshotId", text(128));
        Map<String, Object> success;
        if (plan) {
            success = object(Map.of(
                    "ok",
                    Map.of("const", true),
                    "snapshotId",
                    text(128),
                    "sourceState",
                    McpDocumentFileSchemas.state(),
                    "source",
                    source(),
                    "sourceHash",
                    text(64),
                    "settings",
                    settings(),
                    "executeRequest",
                    object(Map.of(
                            "operationId",
                            text(128),
                            "snapshotId",
                            text(128),
                            "grantId",
                            text(128),
                            "writePolicy",
                            Map.of("const", "REPLACE_AUTHORIZED_FILE"))),
                    "writeAdmission",
                    Map.of("const", "NOT_CHECKED"),
                    "fullCharacterDelivery",
                    Map.of("const", false)));
        } else {
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", Map.of("type", "boolean"));
            output.put("operationId", text(128));
            output.put(
                    "phase",
                    Map.of("enum", List.of("QUEUED", "EXPORTING", "ENCODING", "WRITE_PENDING", "WRITING", "TERMINAL")));
            output.put(
                    "outcome",
                    Map.of("enum", List.of("RUNNING", "CANCELLED", "NOT_WRITTEN", "WRITTEN", "OUTCOME_UNKNOWN")));
            output.put("code", text(128));
            output.put("startedAt", text(64));
            output.put("completedAt", nullable(text(64)));
            for (String key : List.of(
                    "exportAdmitted", "writeAdmitted", "cancelRequested", "sourceStateVerified", "fileWriteConfirmed"))
                output.put(key, Map.of("type", "boolean"));
            for (String key : List.of(
                    "hostCancellationConfirmed", "fileReadbackVerified", "playerLoadVerified", "fullCharacterDelivery"))
                output.put(key, Map.of("const", false));
            output.put("sourceVerificationScope", Map.of("const", "HISTORY_AND_ATLAS_METADATA"));
            output.put("archiveBytes", nullable(integer(1, McpMocPackage.MAX_ARCHIVE)));
            output.put("archiveSha256", nullable(text(64)));
            output.put("manifest", nullable(manifest()));
            success = object(output);
        }
        return Map.of(
                "name",
                name,
                "title",
                plan
                        ? "Plan authorized MOC package"
                        : execute
                                ? "Export authorized MOC package"
                                : cancel ? "Request MOC export cancellation" : "Read MOC export result",
                "description",
                "Official MOC5.3 plus PNG atlas/static model3/manifest ZIP, not full character delivery. Plan reads bounded atlas metadata/history and Core inspection availability, normalizes explicit float export settings and retains a same-session immutable plan for5minutes (16plans). No exporter/inspection/file/grant consumption or operation reservation during plan; writeAdmission NOT_CHECKED. Execute accepts exact issued executeRequest; repeats never re-export/write. One active job,128retained identities, no eviction. Source verification covers document/model/history and atlas id/name/order/dimensions/version/image-count only, not a full immutable geometry snapshot. Runtime exporter also guards native instance/texture membership; exact host/permission admission remains authoritative. Native output is capped by SDK allocations before client post-return budgets (64MiB MOC,128MiB archive,33554432 total texture pixels; per page8192 and16777216pixels). Checks settings/texture count/index dimensions and Core MOC V5_3 CONSISTENT before encoding; unknown/inconsistent/unavailable fails closed. Generated paths only: model.moc3, textures/texture_NN.png, model.model3.json, export-manifest.json. PNG preserves owned top-left ARGB; texture order is native MOC index order. Fixed static model3 refs and per-artifact size/SHA metadata; no physics/motion/expression/pose/display/group inference. Before write, recheck plan TTL/source/history and consume same-session one-use WRITE grant with exactly zip extension filter. Explicit REPLACE_AUTHORIZED_FILE permits atomic replacement of that user-selected target, not arbitrary paths. Pending SDK write retains lease/global slot until actual completion; no cancel of its stage. Cancel/close/disconnect only prevent write admission and never claim admitted native export or file write cancelled/rolled back. written=false is NOT_WRITTEN per SDK atomic contract; lost/exceptional completion is OUTCOME_UNKNOWN, never blind retry. WRITTEN records SDK atomic-write confirmation, not file reread, durable fsync/player loading/real-host acceptance. No binary/base64/file paths in results; only metadata/hash. Not transactionEligible or Undoable. Session/restart drops result access, not admitted filesystem effects.",
                "inputSchema",
                object(input),
                "outputSchema",
                Map.of("oneOf", List.of(success, object(Map.of("ok", Map.of("const", false), "error", text(128))))),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        plan || name.equals(McpMocExportDomain.RESULT),
                        "destructiveHint",
                        execute,
                        "idempotentHint",
                        !plan,
                        "openWorldHint",
                        false));
    }

    static Map<String, Object> settings() {
        var fields = new LinkedHashMap<String, Object>();
        for (String key : List.of("originX", "originY"))
            fields.put(key, Map.of("type", "number", "minimum", -Float.MAX_VALUE, "maximum", Float.MAX_VALUE));
        fields.put("pixelsPerUnit", Map.of("type", "number", "exclusiveMinimum", 0, "maximum", Float.MAX_VALUE));
        for (String key : List.of(
                "includeInvisibleParts",
                "includeSketchParts",
                "includeInvisibleMeshes",
                "includeInvisibleAliases",
                "extendEdgeColors")) fields.put(key, Map.of("type", "boolean"));
        return object(fields);
    }

    private static Map<String, Object> source() {
        return object(Map.of(
                "totalPixels",
                integer(1, McpMocPackage.MAX_PIXELS),
                "atlases",
                array(
                        object(Map.of(
                                "id",
                                text(4096),
                                "name",
                                text(128),
                                "width",
                                integer(1, 8192),
                                "height",
                                integer(1, 8192),
                                "atlasVersion",
                                Map.of("type", "integer"),
                                "modelImageCount",
                                integer(0, Integer.MAX_VALUE))),
                        1,
                        32)));
    }

    private static Map<String, Object> manifest() {
        var artifact = object(Map.of(
                "path",
                text(64),
                "mimeType",
                text(64),
                "byteLength",
                integer(1, McpMocPackage.MAX_ARCHIVE),
                "sha256",
                text(64),
                "textureIndex",
                nullable(integer(0, 31)),
                "width",
                nullable(integer(1, 8192)),
                "height",
                nullable(integer(1, 8192))));
        return object(Map.of(
                "schemaVersion",
                Map.of("const", 1),
                "format",
                Map.of("const", "MOC_5_3_STATIC_PACKAGE"),
                "sourceState",
                McpDocumentFileSchemas.state(),
                "sourceHash",
                text(64),
                "settings",
                settings(),
                "artifacts",
                array(artifact, 3, 34),
                "mocValidation",
                object(Map.of("version", Map.of("const", "V5_3"), "consistency", Map.of("const", "CONSISTENT"))),
                "fullCharacterDelivery",
                Map.of("const", false),
                "omittedAssets",
                array(text(64), 6, 6)));
    }

    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> integer(long min, long max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> nullable(Object schema) {
        return Map.of("oneOf", List.of(schema, Map.of("type", "null")));
    }

    private static Map<String, Object> array(Object schema, int min, int max) {
        return Map.of("type", "array", "items", schema, "minItems", min, "maxItems", max);
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
