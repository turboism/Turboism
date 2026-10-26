package dev.turboism.plugin.mcp;

import java.util.*;

final class McpImageRegistrationSchemas {
    static Map<String, Object> definition(String name) {
        boolean start = name.equals(McpImageRegistrationDomain.START),
                cancel = name.equals(McpImageRegistrationDomain.CANCEL);
        var input = new LinkedHashMap<String, Object>();
        input.put("operationId", text(128));
        if (start) {
            for (String field : List.of("documentId", "modelId", "objectId")) input.put(field, text(4096));
            input.put("snapshotId", text(128));
            input.put("grantId", text(128));
            input.put("name", text(128));
            input.put("maxPngBytes", integer(1, McpImageRegistrationDomain.MAX_PNG_BYTES));
            input.put("maxPixels", integer(1, McpImageRegistrationDomain.MAX_PIXELS));
            input.put("expectedSha256", Map.of("type", "string", "pattern", "^[0-9a-f]{64}$"));
        }
        var required = new ArrayList<>(input.keySet());
        required.remove("expectedSha256");
        @SuppressWarnings("unchecked")
        var writeFields = new LinkedHashMap<>(
                (Map<String, Object>) ((Map<?, ?>) McpImageBindingSchemas.definition(McpImageBindingDomain.BIND)
                                .get("outputSchema"))
                        .get("properties"));
        writeFields.put("rawImageId", Map.of("type", List.of("string", "null"), "maxLength", 4096));
        var file = object(Map.of(
                "sha256",
                Map.of("type", "string", "pattern", "^[0-9a-f]{64}$"),
                "byteLength",
                integer(1, McpImageRegistrationDomain.MAX_PNG_BYTES),
                "width",
                integer(1, 8192),
                "height",
                integer(1, 8192),
                "fileStability",
                Map.of("const", "UNPROVEN")));
        var output = new LinkedHashMap<String, Object>();
        output.put("ok", Map.of("type", "boolean"));
        output.put("operationId", text(128));
        output.put(
                "phase",
                Map.of(
                        "enum",
                        List.of(
                                "QUEUED",
                                "PREFLIGHT",
                                "READING",
                                "VALIDATING",
                                "HOST_PENDING",
                                "HOST_RUNNING",
                                "TERMINAL")));
        output.put(
                "outcome",
                Map.of(
                        "enum",
                        List.of(
                                "RUNNING",
                                "CANCELLED",
                                "NOT_APPLIED",
                                "APPLIED",
                                "NO_CHANGE",
                                "ROLLED_BACK",
                                "OUTCOME_UNKNOWN",
                                "APPLIED_WITH_READBACK_WARNING")));
        output.put("code", text(128));
        output.put("startedAt", text(64));
        output.put("completedAt", Map.of("type", List.of("string", "null")));
        output.put("cancelRequested", Map.of("type", "boolean"));
        output.put("hostAdmitted", Map.of("type", "boolean"));
        output.put("hostCancellationConfirmed", Map.of("const", false));
        output.put("fileEvidence", nullable(file));
        output.put("writeResult", nullable(object(writeFields)));
        return Map.of(
                "name",
                name,
                "title",
                start
                        ? "Register and bind an authorized PNG"
                        : cancel ? "Request PNG registration cancellation" : "Read PNG registration job result",
                "description",
                "Asynchronous single-target authorized PNG registration and ArtMesh binding. Start requires a real assets.binding.read snapshot, same-session one-use READ grant with exactly png extension filter, explicit name and maxPngBytes (1..16MiB)/maxPixels (1..16777216). Optional expectedSha256 pins the actual read content. No paths/base64, files are never written. Preflight rejects bound/locked/parent-deformed targets, stale history/native geometry/parents/catalog and full budgets before grant consumption; all state is rechecked at native admission after the read, including snapshot TTL. Header dimensions must be positive, <=8192 each and within declared pixel budget. SDK bounded AuthorizedImageContent validates PNG header; native Runtime verifies actual decode/dimensions. One job globally retains the read/worker/host slot until actual completion; max128 operation identities, exact replay does not reread/register. Poll result; explicit cancel only prevents native admission before hostAdmitted. Cancel, request Abort, disconnect or server stop never claim native rollback/cancellation after admission. Pending native file reads are not cancelled and retain their lease until actual completion. Full authoring/input/parent/lock/history/registered metadata CAS and owned SDK transaction; new raw image/group/model image and binding must read back exactly, with prior images/UV/topology/forms unchanged. Runtime permissions/exact-host Undo/hierarchy/extension admission remain authoritative. In-transaction failure rolls back or returns recovery failure OUTCOME_UNKNOWN. writeResult is the historical receipt, with actual rawImageId/textureInput when known; post-commit read failure warns, response loss is unknown, never blind retry. Hashes in writeResult cover source GUID and full before/expected-after binding/raw-image metadata (not geometry/pixels); fileEvidence hash covers the exact immutable authorized bytes, not file stability or saved-model association. Results do not retain or return image bytes. Session close prevents pre-host work and drops result access; missing result does not prove unchanged model. Not transactionEligible. No PSD import, arbitrary rebinding, atlas packing, GPU visibility or real-host acceptance claim.",
                "inputSchema",
                Map.of("type", "object", "properties", input, "required", required, "additionalProperties", false),
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(object(output), object(Map.of("ok", Map.of("const", false), "error", text(128))))),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        !start && !cancel,
                        "destructiveHint",
                        false,
                        "idempotentHint",
                        true,
                        "openWorldHint",
                        false));
    }

    private static Map<String, Object> nullable(Object schema) {
        return Map.of("oneOf", List.of(schema, Map.of("type", "null")));
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
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
