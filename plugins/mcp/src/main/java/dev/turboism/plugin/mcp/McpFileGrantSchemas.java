package dev.turboism.plugin.mcp;

import java.util.*;

final class McpFileGrantSchemas {
    static Map<String, Object> definition(String name) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("operationId", text(128));
        if (name.equals(McpFileGrantDomain.REQUEST)) {
            fields.put("mode", Map.of("enum", List.of("READ", "WRITE")));
            fields.put("purpose", text(200));
            fields.put("allowedExtensions", Map.of("type", "array", "minItems", 1, "maxItems", 8, "items", text(32)));
        }
        return Map.of(
                "name",
                name,
                "title",
                name.equals(McpFileGrantDomain.REQUEST)
                        ? "Request user file grant"
                        : name.equals(McpFileGrantDomain.STATUS) ? "Read file grant status" : "Revoke file grant",
                "description",
                "Session-bound asynchronous SDK file chooser. No paths, native handles, file contents or automatic file commands. Request requires purpose, READ or WRITE and 1-8 extension filters; returns immediately, poll grant_status by operationId. Grant/request TTL 5 minutes from admission, no renewal. At most one native pending chooser and 128 retained request identities per server; exact replay never opens another chooser. Revocation/expiry/session close discards late grants but does not claim the native dialog was canceled. No Undo or filesystem change. Service/permission/host may be unavailable. Opaque grantId is valid only in this MCP session/process; file consumers are a separate capability. Status of a request is not proof that a file was opened, saved or persisted.",
                "inputSchema",
                object(fields),
                "outputSchema",
                output(),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        name.equals(McpFileGrantDomain.STATUS),
                        "destructiveHint",
                        false,
                        "idempotentHint",
                        true,
                        "openWorldHint",
                        false));
    }

    private static Map<String, Object> output() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("ok", Map.of("const", true));
        fields.put("operationId", text(128));
        fields.put(
                "state",
                Map.of(
                        "enum",
                        List.of(
                                "PENDING",
                                "GRANTED",
                                "CONSUMED",
                                "CANCELED",
                                "DENIED",
                                "UNAVAILABLE",
                                "FAILED",
                                "REVOKED",
                                "EXPIRED",
                                "CLOSED",
                                "SESSION_CLOSED",
                                "SERVER_CLOSED")));
        fields.put("mode", Map.of("enum", List.of("READ", "WRITE")));
        fields.put("expiresAt", text(64));
        fields.put("grantId", Map.of("type", List.of("string", "null"), "maxLength", 128));
        fields.put("nativeChooserPending", Map.of("type", "boolean"));
        fields.put("hostCancellationConfirmed", Map.of("const", false));
        return Map.of(
                "oneOf", List.of(object(fields), object(Map.of("ok", Map.of("const", false), "error", text(128)))));
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
