package dev.turboism.plugin.mcp;

import java.util.*;

/** Closed, path-free contracts for existing authored model image bindings. */
final class McpImageBindingSchemas {
    static Map<String, Object> definition(String name) {
        boolean read = name.equals(McpImageBindingDomain.READ), result = name.equals(McpImageBindingDomain.RESULT);
        var input = new LinkedHashMap<String, Object>();
        if (!result) for (String field : List.of("documentId", "modelId", "objectId")) input.put(field, text(4096));
        if (read) input.put("authoringSnapshotId", text(128));
        else {
            input.put("operationId", text(128));
            if (!result) {
                input.put("snapshotId", text(128));
                input.put("modelImageId", text(4096));
            }
        }
        var bindingInput = textureInput();
        Object output;
        if (read) {
            var image = object(Map.of(
                    "id",
                    text(4096),
                    "name",
                    Map.of("type", "string", "maxLength", 4096),
                    "width",
                    integer(1, Integer.MAX_VALUE),
                    "height",
                    integer(1, Integer.MAX_VALUE),
                    "groupIndex",
                    integer(0, 1023)));
            var binding = object(Map.of(
                    "groupCount",
                    integer(0, 1024),
                    "locked",
                    Map.of("type", "boolean"),
                    "textureInput",
                    bindingInput,
                    "images",
                    Map.of("type", "array", "maxItems", McpImageBindingDomain.MAX_IMAGES, "items", image)));
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
                                    "objectId",
                                    text(4096),
                                    "sourceGuid",
                                    text(4096),
                                    "snapshotId",
                                    text(128),
                                    "state",
                                    state,
                                    "binding",
                                    binding)),
                            object(Map.of("ok", Map.of("const", false), "error", text(128)))));
        } else {
            @SuppressWarnings("unchecked")
            var properties = new LinkedHashMap<>(
                    (Map<String, Object>) McpWriteResults.schema().get("properties"));
            properties.put("textureInput", bindingInput);
            output = object(properties);
        }
        return Map.of(
                "name",
                name,
                "title",
                read
                        ? "Observe an ArtMesh image binding"
                        : result ? "Read retained image binding result" : "Bind an existing model image",
                "description",
                read
                        ? "Read authored ArtMesh texture input (MODEL_IMAGE/TEXTURE_ATLAS_REGION or truly unbound) and registered model image candidates. Requires issued meshes.read snapshot and full native/parent/history CAS. Issues session snapshot valid 5 minutes in the shared 16-entry/16MiB authoring cache, including retained native state. Max 1024 groups/images and 240KiB response; no path, bytes, pose or rendering. Names are metadata, never semantic layer mapping; dimensions/identity do not prove GPU sampling. Snapshot lock is source-level; native hierarchy lock/extension/host admission remains authoritative."
                        : result
                                ? "Session-local retained binding operation result without host reads. Missing result means OUTCOME_UNKNOWN, not proof the model is unchanged. Timeout/Abort is not host cancellation; query this result before any retry. Retained until server stops, max 256 admitted operations; no eviction/re-execution."
                                : "Bind one discovered existing modelImageId using a binding.read snapshot. Full native authoring, parent, lock, history, previous input and registered candidate metadata CAS is checked before and inside one owned SDK authoring transaction. Unbound unlocked ArtMesh with no parent deformer only; same existing image is verified NO_CHANGE without invoking bind. Other images/atlas inputs are rejected, never overwritten; native extension/hierarchy locks/permissions/exact host and Undo admission still fail closed. SDK ModelImageBinding model/image/mesh/generation and exact textureInput are checked inside transaction; failures roll back. Geometry/UV/topology/forms/parents/candidates must remain identical. Hashes cover document/model/mesh/source GUID and input kind/resource IDs only, excluding native generation and geometry (separately CAS-checked). Response textureInput is exact successful post-commit readback; null otherwise. operationId reserves one session result; identical replay returns it without host reads, conflicts fail. APPLIED_WITH_READBACK_WARNING and OUTCOME_UNKNOWN are never safe blind retry. Not transactionEligible; do not compose legacy writes as atomic. No image registration/upload/rebinding, atlas packing, pixels or visual acceptance.",
                "inputSchema",
                object(input),
                "outputSchema",
                output,
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

    private static Map<String, Object> textureInput() {
        var alternatives = new ArrayList<Object>();
        alternatives.add(Map.of("type", "null"));
        for (String kind : List.of("MODEL_IMAGE", "TEXTURE_ATLAS_REGION")) {
            var fields = new LinkedHashMap<String, Object>();
            fields.put("artMeshId", text(4096));
            fields.put("modelId", text(4096));
            fields.put("modelGeneration", integer(1, Long.MAX_VALUE));
            fields.put("kind", Map.of("const", kind));
            fields.put("modelImageId", kind.equals("MODEL_IMAGE") ? text(4096) : Map.of("type", "null"));
            fields.put("textureAtlasId", kind.equals("TEXTURE_ATLAS_REGION") ? text(4096) : Map.of("type", "null"));
            alternatives.add(object(fields));
        }
        return Map.of("oneOf", alternatives);
    }

    private static Map<String, Object> integer(long min, long max) {
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
