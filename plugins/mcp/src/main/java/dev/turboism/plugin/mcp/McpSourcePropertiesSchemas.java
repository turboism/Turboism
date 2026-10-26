package dev.turboism.plugin.mcp;

import java.util.*;

final class McpSourcePropertiesSchemas {
    static Map<String, Object> definition(String name) {
        boolean read = name.equals(McpSourcePropertiesDomain.READ),
                result = name.equals(McpSourcePropertiesDomain.RESULT);
        Map<String, Object> input;
        if (read)
            input = object(Map.of(
                    "documentId",
                    text(),
                    "modelId",
                    text(),
                    "kind",
                    Map.of("enum", List.of("art_mesh", "warp_deformer", "rotation_deformer")),
                    "objectId",
                    text(),
                    "authoringSnapshotId",
                    shortId()));
        else if (result) input = object(Map.of("operationId", shortId()));
        else {
            var targets = new ArrayList<Map<String, Object>>();
            for (String kind : List.of("art_mesh", "warp_deformer", "rotation_deformer"))
                targets.add(object(Map.of(
                        "kind",
                        Map.of("const", kind),
                        "objectId",
                        text(),
                        "snapshotId",
                        shortId(),
                        "properties",
                        patch(kind))));
            input = object(Map.of(
                    "documentId",
                    text(),
                    "modelId",
                    text(),
                    "operationId",
                    shortId(),
                    "targets",
                    Map.of("type", "array", "minItems", 1, "maxItems", 8, "items", Map.of("oneOf", targets))));
        }
        return Map.of(
                "name",
                name,
                "title",
                read
                        ? "Read source object properties"
                        : result ? "Read retained source property result" : "Write explicit source object properties",
                "description",
                read
                        ? "Read own source visibility/lock for ArtMesh/Warp/Rotation plus ArtMesh culling, using a same-session authoring snapshot. Full native stored state/parent/history CAS. No evaluated/effective visibility or current-form opacity/color interpretation. Issued property snapshots use shared authoring TTL and byte/count budgets; no pose/selection changes."
                        : result
                                ? "Read current-session process-local operation result without waiting for UI. Missing means unknown, not unchanged; results capped at 256 without eviction until server stops."
                                : "Closed source-level properties only: visible/locked for ArtMesh/Warp/Rotation and culling for ArtMesh. Use issued property snapshotId per target, explicit nonempty Boolean patches and unique operationId. Full native stored state, parents, history and all supported own properties checked before/inside one SDK transaction and after write. Max 8 objects, 128KiB request, 256 retained operations without eviction; session/process-local results. No automatic unlocking or ancestor edits; explicit locked:false changes only that object's source flag. No Part, current/ordinary form opacity/colors/composition or arbitrary setters. No default-form writes, pose movement or selection. Own transaction cannot nest in transaction.execute. Exact native support/Undo remains authoritative. After timeout query properties.result; do not blindly replay.",
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
        var branches = new ArrayList<Map<String, Object>>();
        var state = new LinkedHashMap<String, Object>();
        for (String k : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            state.put(k, Map.of("type", "string"));
        for (String k : List.of("historyGeneration", "historyRevision"))
            state.put(k, Map.of("type", "integer", "minimum", 0));
        for (String kind : List.of("art_mesh", "warp_deformer", "rotation_deformer")) {
            var properties = new LinkedHashMap<String, Object>();
            for (String field : fields(kind)) properties.put(field, Map.of("type", "boolean"));
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", Map.of("const", true));
            output.put("kind", Map.of("const", kind));
            output.put("objectId", text());
            output.put("sourceGuid", text());
            output.put("snapshotId", shortId());
            output.put("state", object(state));
            output.put("scope", Map.of("const", "source_object"));
            output.put("properties", object(properties));
            branches.add(object(output));
        }
        branches.add(object(Map.of("ok", Map.of("const", false), "error", text())));
        return Map.of("oneOf", branches);
    }

    private static Map<String, Object> patch(String kind) {
        var names = fields(kind);
        var alternatives = new ArrayList<Map<String, Object>>();
        for (int mask = 1; mask < (1 << names.size()); mask++) {
            var selected = new LinkedHashMap<String, Object>();
            for (int i = 0; i < names.size(); i++)
                if ((mask & (1 << i)) != 0) selected.put(names.get(i), Map.of("type", "boolean"));
            alternatives.add(object(selected));
        }
        return Map.of("oneOf", alternatives);
    }

    private static List<String> fields(String kind) {
        return kind.equals("art_mesh") ? List.of("visible", "locked", "culling") : List.of("visible", "locked");
    }

    private static Map<String, Object> text() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 4096);
    }

    private static Map<String, Object> shortId() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 128);
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
