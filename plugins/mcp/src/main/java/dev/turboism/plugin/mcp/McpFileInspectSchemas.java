package dev.turboism.plugin.mcp;

import java.util.*;

final class McpFileInspectSchemas {
    static Map<String, Object> definition(String name) {
        boolean inspect = name.equals(McpFileInspectDomain.INSPECT);
        var input = new LinkedHashMap<String, Object>();
        input.put("operationId", text(128));
        if (inspect) {
            input.put("grantId", text(128));
            input.put("maxBytes", Map.of("type", "integer", "minimum", 1, "maximum", McpFileInspectDomain.MAX_BYTES));
        }
        var output = new LinkedHashMap<String, Object>();
        output.put("ok", Map.of("type", "boolean"));
        output.put("operationId", text(128));
        output.put("outcome", Map.of("enum", List.of("RUNNING", "COMPLETE", "TRUNCATED", "FAILED")));
        output.put("code", text(128));
        output.put("startedAt", text(64));
        output.put("completedAt", Map.of("type", List.of("string", "null")));
        output.put("maxBytes", Map.of("type", "integer", "minimum", 1, "maximum", McpFileInspectDomain.MAX_BYTES));
        output.put("byteLength", Map.of("type", List.of("integer", "null"), "minimum", 0));
        output.put("sha256", Map.of("type", List.of("string", "null"), "maxLength", 64));
        output.put("fileStability", Map.of("const", "UNPROVEN"));
        output.put("documentAssociation", Map.of("const", "UNVERIFIED"));
        output.put("hostCancellationConfirmed", Map.of("const", false));
        return Map.of(
                "name",
                name,
                "title",
                inspect ? "Inspect authorized file bytes" : "Read retained file inspection",
                "description",
                "Asynchronous bounded file observation using a same-session READ grant consumed once. Returns only byte count and SHA-256 after a nontruncated SDK read; never paths, native handles, file bytes or prefix hashes. Explicit maxBytes 1..128MiB; SDK fallback above 16MiB can be unavailable. One native read/hash in flight globally, 128 operation identities without replay-enabling eviction. Poll inspect_result; exact replay never reads again. Request consumes a grant and requires Bearer, no filesystem writes or Undo. Session close/server stop discard late results without claiming native I/O cancellation; lease cleanup waits for actual native completion. COMPLETE means one full bounded read, not an immutable filesystem snapshot, fsync/durability, association with a saved model, format validation or successful reopen. File may change during/after read; fileStability UNPROVEN and documentAssociation UNVERIFIED. Runtime may transiently allocate multiple buffers within its read budget; no file bytes are retained in result cache.",
                "inputSchema",
                object(input),
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(object(output), object(Map.of("ok", Map.of("const", false), "error", text(128))))),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        !inspect,
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
