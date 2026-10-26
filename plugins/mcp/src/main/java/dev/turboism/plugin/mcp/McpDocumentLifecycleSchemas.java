package dev.turboism.plugin.mcp;

import java.util.*;

final class McpDocumentLifecycleSchemas {
    static Map<String, Object> definition(String name) {
        boolean read = name.equals(McpDocumentLifecycleDomain.READ),
                result = name.equals(McpDocumentLifecycleDomain.RESULT);
        var input = new LinkedHashMap<String, Object>();
        if (!read) {
            input.put("operationId", text(128));
            if (!result) input.put("snapshotId", text(128));
        }
        String description = read
                ? "Read bounded project document/content identity metadata and the active document, including empty/no-document sessions. No paths, resource pixels, native handles, pose or selection changes. Up to128 documents/128 contents/256 content-document associations/192KiB; native facade snapshot allocation precedes checks. Names are user-visible metadata. Captures declared fields twice, plus active model authoring/history binding when available; missing binding remains null. This is not full model geometry CAS, native document GUID proof or a whole-project asset inventory. 16 process snapshots, session-bound,TTL5min. writeAdmission=NOT_CHECKED."
                : result
                        ? "Read retained same-session NEW_MODEL outcome without host reads or redispatch. Max128 operation identities, no eviction/restart recovery. Missing is OUTCOME_UNKNOWN. This is not a live poll of eventual UI completion: after EXECUTED_NOT_READY or unknown, use documents.read to inspect current identities and obtain human confirmation as needed; never blindly create again."
                        : "Invoke only typed NEW_MODEL, once, against an issued documents.read snapshot. Declared document metadata/active authoring binding rechecked twice and NEW_MODEL must appear in live available commands. EXTERNAL_SIDE_EFFECT: no Undo, no authoring transaction, no close/open/save or arbitrary command dispatch. No caller-specified model name/canvas is implied: native defaults/UI apply. READY requires a newly observed active MODEL document with unique new model ID, active authoring/history binding, preserved prior document metadata/order and prior content metadata. This checks authoring identity readiness, not full existing model contents, geometry, canvas defaults, render or persistence. Native EXECUTED without confirmed new target becomes EXECUTED_NOT_READY (isError=true), never READY. FAILED/exception after dispatch or unreadable post-state is OUTCOME_UNKNOWN. UI dialogs/async transitions may require user interaction and a fresh documents.read; no repeated command to probe readiness. OperationId replay/results perform zero host reads. Snapshots5min/16;128 retained operations. Queued cancellation prevents dispatch only; stopping the bridge is not native cancellation.";
        return Map.of(
                "name",
                name,
                "title",
                read
                        ? "Read project document identities"
                        : result ? "Read new model command result" : "Create and inspect a new model document",
                "description",
                description,
                "inputSchema",
                object(input),
                "outputSchema",
                read
                        ? success(object(Map.of(
                                "ok",
                                constant(true),
                                "snapshotId",
                                text(128),
                                "state",
                                state(),
                                "stateHash",
                                text(4096),
                                "snapshotTtlSeconds",
                                constant(300),
                                "identityScope",
                                constant("DECLARED_DOCUMENT_METADATA_AND_ACTIVE_AUTHORING_BINDING"),
                                "writeAdmission",
                                constant("NOT_CHECKED"))))
                        : success(resultSchema()),
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

    private static Map<String, Object> document() {
        return object(Map.of(
                "documentId",
                text(4096),
                "name",
                text(4096),
                "kind",
                Map.of("enum", List.of("MODEL", "ANIMATION_SCENE", "IMAGE", "GAME_DATA", "PHYSICS_SETTINGS", "OTHER")),
                "contentId",
                nullable(),
                "modelId",
                nullable()));
    }

    private static Map<String, Object> state() {
        var content = object(Map.of(
                "contentId",
                text(4096),
                "name",
                text(4096),
                "kind",
                Map.of("enum", List.of("MODEL", "ANIMATION", "IMAGE", "GAME_DATA", "OTHER")),
                "documentIds",
                array(text(4096), 256)));
        var project = object(Map.of(
                "projectId",
                text(4096),
                "name",
                text(4096),
                "documents",
                array(document(), 128),
                "contents",
                array(content, 128)));
        return object(Map.of(
                "hostObserved",
                Map.of("type", "boolean"),
                "project",
                nullable(project),
                "activeDocument",
                nullable(document()),
                "authoringState",
                nullable(McpDocumentFileSchemas.state())));
    }

    private static Map<String, Object> resultSchema() {
        var p = new LinkedHashMap<String, Object>();
        p.put("ok", Map.of("type", "boolean"));
        p.put("operationId", text(128));
        p.put(
                "outcome",
                Map.of(
                        "enum",
                        List.of("QUEUED", "RUNNING", "READY", "EXECUTED_NOT_READY", "NOT_APPLIED", "OUTCOME_UNKNOWN")));
        p.put("code", text(128));
        p.put("nativeStatus", nullable());
        p.put("afterState", nullable(state()));
        p.put("authoringReady", Map.of("type", "boolean"));
        for (String key : List.of("existingModelContentsVerified", "persistenceVerified", "retrySafe"))
            p.put(key, constant(false));
        return object(p);
    }

    private static Map<String, Object> success(Map<String, Object> s) {
        return Map.of("oneOf", List.of(s, object(Map.of("ok", constant(false), "error", text(128)))));
    }

    private static Map<String, Object> nullable(Map<String, Object> s) {
        return Map.of("oneOf", List.of(s, Map.of("type", "null")));
    }

    private static Map<String, Object> nullable() {
        return Map.of("type", List.of("string", "null"), "minLength", 1, "maxLength", 4096);
    }

    private static Map<String, Object> text(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> array(Map<String, Object> item, int max) {
        return Map.of("type", "array", "items", item, "maxItems", max);
    }

    private static Map<String, Object> constant(Object value) {
        return Map.of("const", value);
    }

    private static Map<String, Object> object(Map<String, Object> p) {
        return Map.of(
                "type", "object", "properties", p, "required", List.copyOf(p.keySet()), "additionalProperties", false);
    }
}
