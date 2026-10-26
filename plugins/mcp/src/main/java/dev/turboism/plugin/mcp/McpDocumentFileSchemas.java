package dev.turboism.plugin.mcp;

import java.util.*;

final class McpDocumentFileSchemas {
    static Map<String, Object> state() {
        var fields = new LinkedHashMap<String, Object>();
        for (String key : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            fields.put(key, text(4096));
        for (String key : List.of("historyGeneration", "historyRevision"))
            fields.put(key, Map.of("type", "integer", "minimum", 0));
        return object(fields);
    }

    static Map<String, Object> definition(String name) {
        boolean save = name.equals(McpDocumentFileDomain.SAVE_AS);
        boolean open = name.equals(McpDocumentFileDomain.OPEN);
        var input = new LinkedHashMap<String, Object>();
        input.put("operationId", text(128));
        if (save) {
            input.put("grantId", text(128));
            input.put("expectedState", state());
            input.put("overwritePolicy", Map.of("enum", List.of("REJECT_EXISTING")));
        }
        if (open) {
            input.put("grantId", text(128));
            input.put("documentSnapshotId", text(128));
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("ok", Map.of("type", "boolean"));
        output.put("operationId", text(128));
        output.put(
                "outcome", Map.of("enum", List.of("QUEUED", "RUNNING", "EXECUTED", "NOT_APPLIED", "OUTCOME_UNKNOWN")));
        output.put("code", text(128));
        output.put("nativeStatus", Map.of("type", List.of("string", "null")));
        output.put("afterState", Map.of("oneOf", List.of(state(), Map.of("type", "null"))));
        for (String key : List.of("persistenceVerified", "reopenVerified", "retrySafe"))
            output.put(key, Map.of("const", false));
        return Map.of(
                "name",
                name,
                "title",
                save
                        ? "Save model to authorized destination"
                        : open ? "Open authorized CMO3" : "Read document file operation result",
                "description",
                open
                        ? "Dispatch typed OPEN for CMO3 only on exact Cubism 5.3.03. Requires a same-session one-use READ grant with allowedExtensions=[cmo3], plus a current documents.read snapshot; works with an empty document inventory. Rechecks declared document metadata and active authoring binding before grant consumption and immediately before dispatch. SDK requires model read/write and file read permission; canonical regular-file use-point checks apply. Does not close existing documents, save, or bypass chooser approval. EXECUTED only means native open returned: it can focus an existing tab or trigger async/UI work. afterState is best-effort active authoring readback, never proof that the authorized file loaded or freshly deserialized. Rediscover documents and geometry; no readiness/persistence/reopen claim. Uncertain native errors/timeouts require file_result, never replay. Results retained once per operation/session, no raw paths/handles."
                        : save
                                ? "Invokes typed native SAVE_AS on the expected active model document using a same-session WRITE grant restricted to allowedExtensions=[cmo3], consumed once at host admission and released after completion. expectedState is the six-field stored-authoring state, not a geometry snapshot or persistence proof. Validates document/model/history before consumption and immediately before native dispatch. Explicit REJECT_EXISTING required; replacement is excluded until user-visible overwrite approval is represented. SDK/runtime file authorization and use-point guards remain authoritative. EXTERNAL_SIDE_EFFECT, no Undo, no authoring transaction. Exact operationId replay returns retained outcome without another save; up to 128 shared file operation identities/process without eviction. After timeout query documents.file_result, never blindly retry. EXECUTED means native command reported success (possibly a no-op), not verified bytes, hashes, source preservation or reopen consistency. FAILED/exception after dispatch is OUTCOME_UNKNOWN. No paths or native handles."
                                : "Read same-session process-local file operation without waiting for UI. QUEUED/RUNNING/EXECUTED/NOT_APPLIED/OUTCOME_UNKNOWN are distinct. Missing result does not prove no filesystem effects. Native command completion does not prove persistence/reopen; recovery across sessions/restarts is not provided.",
                "inputSchema",
                object(input),
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(object(output), object(Map.of("ok", Map.of("const", false), "error", text(128))))),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        !save && !open,
                        "destructiveHint",
                        save || open,
                        "idempotentHint",
                        true,
                        "openWorldHint",
                        false));
    }

    private static Map<String, Object> text(int max) {
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
