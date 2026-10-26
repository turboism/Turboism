package dev.turboism.plugin.mcp;

import java.util.*;

final class McpMaskSchemas {
    static Map<String, Object> definition(String name) {
        boolean read = name.equals(McpMaskDomain.READ), result = name.equals(McpMaskDomain.RESULT);
        Map<String, Object> input;
        if (read) input = object(Map.of("documentId", id(), "modelId", id(), "objectIds", array(id(), 1, 64)));
        else if (result) input = object(Map.of("operationId", shortId()));
        else
            input = object(Map.of(
                    "documentId",
                    id(),
                    "modelId",
                    id(),
                    "snapshotId",
                    shortId(),
                    "operationId",
                    shortId(),
                    "replacements",
                    array(
                            object(Map.of(
                                    "objectId",
                                    id(),
                                    "maskIds",
                                    array(id(), 1, 64),
                                    "inverted",
                                    Map.of("type", "boolean"))),
                            1,
                            8)));
        return Map.of(
                "name",
                name,
                "title",
                read
                        ? "Read ArtMesh mask dependency snapshot"
                        : result ? "Read retained mask operation result" : "Replace ArtMesh masks conditionally",
                "description",
                read
                        ? "Read explicit ArtMesh IDs plus transitive current mask dependencies with GUID and parent identity. Session snapshot TTL 5 minutes, 16 retained snapshots, max 64 objects and 64KiB. No pose, selection, render or writes. Include proposed mask sources in objectIds before planning a write."
                        : result
                                ? "Read process-local current-session mask operation result without waiting for UI. Missing means unknown, not unchanged. 256 results retained without eviction until server stops; no restart recovery."
                                : "Replace nonempty mask lists/inversion for up to 8 objects using a session-issued masks.read snapshot. All targets and proposed mask IDs must be in that snapshot. Full dependency mask state/GUID/parent/history checked before and inside one SDK transaction, with full mask readback. No clearing masks (SDK batch forbids empty), no new mask cycles. Max 128KiB request; no geometry or pose changes. Reuse exact operationId request for retained result, query masks.result after timeout, never blindly replay. Own transaction; cannot nest in transaction.execute. Exact native clip-mask/Undo support remains authoritative; no visual acceptance claim.",
                "inputSchema",
                input,
                "outputSchema",
                read ? readOutput() : McpWriteResults.schema(),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        read || result,
                        "destructiveHint",
                        false,
                        "idempotentHint",
                        true,
                        "openWorldHint",
                        false));
    }

    private static Map<String, Object> readOutput() {
        var stateFields = new LinkedHashMap<String, Object>();
        for (String k : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            stateFields.put(k, id());
        for (String k : List.of("historyGeneration", "historyRevision"))
            stateFields.put(k, Map.of("type", "integer", "minimum", 0));
        var mask = object(Map.of(
                "objectId",
                id(),
                "sourceGuid",
                id(),
                "parentPartId",
                nullable(),
                "parentDeformerId",
                nullable(),
                "maskIds",
                array(id(), 0, 64),
                "inverted",
                Map.of("type", "boolean")));
        return Map.of(
                "oneOf",
                List.of(
                        object(Map.of(
                                "ok",
                                Map.of("const", true),
                                "snapshotId",
                                shortId(),
                                "snapshot",
                                object(Map.of("state", object(stateFields), "objects", array(mask, 1, 64))))),
                        object(Map.of("ok", Map.of("const", false), "error", id()))));
    }

    private static Map<String, Object> id() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096);
    }

    private static Map<String, Object> shortId() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 128);
    }

    private static Map<String, Object> nullable() {
        return Map.of("type", List.of("string", "null"));
    }

    private static Map<String, Object> array(Map<String, Object> item, int min, int max) {
        return Map.of("type", "array", "items", item, "minItems", min, "maxItems", max);
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
