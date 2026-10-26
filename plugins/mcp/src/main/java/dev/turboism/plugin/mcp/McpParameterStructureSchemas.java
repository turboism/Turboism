package dev.turboism.plugin.mcp;

import java.util.*;

final class McpParameterStructureSchemas {
    static Map<String, Object> definition(String name) {
        boolean read = name.equals(McpParameterStructureDomain.READ),
                plan = name.equals(McpParameterStructureDomain.PLAN),
                result = name.equals(McpParameterStructureDomain.RESULT);
        var input = new LinkedHashMap<String, Object>();
        if (!result) {
            input.put("documentId", id());
            input.put("modelId", id());
        }
        if (plan) {
            input.put("snapshotId", shortId());
            input.put("edits", array(edit(), 1, 16));
        }
        if (!read && !plan) {
            input.put("operationId", shortId());
            if (!result) input.put("planId", shortId());
        }
        String description = read
                ? "Read whole bounded parameter folder/parameter organization without changing pose, selection or UI. Actual mixed children order, definitions and nullable Combined metadata; native parameter GUIDs, no geometry/binding/value snapshot or native folder GUID. Duplicate IDs or inconsistent tree/pairs fail closed. Max128 folders/512 parameters/192KiB,16 session snapshots,TTL5min. Legacy indexed/mixed readers may be unavailable."
                : plan
                        ? "Pure sequential preview of up to16 closed edits against a session snapshot, verified against current full structural fields and six history bindings. create_group is root-only; ref can be used by later edits as createdRef. Provisional IDs are never native IDs. Remove requires exact pre-order descendant group IDs and model-order parameter IDs, including the folder itself; it deletes descendant parameters and their rig data. No group reparent/reorder. No write admission test or host mutation. Plans share source TTL and max16 retained. Normal parameters only for combine; moving a paired parameter or splitting another pair is rejected; uncombine first."
                        : result
                                ? "Read current-session retained operation result without host/UI reads. 256 operation IDs, no eviction; unknown after process restart. Missing is OUTCOME_UNKNOWN, not unchanged. createdGroups in failed/unknown results are observations, not guaranteed live IDs."
                                : "Apply an immutable structure.plan once inside its own SDK authoring transaction. Structural fields and full history rechecked before/inside; per-step predicted mixed order, definitions and pair readback; post-rollback structure verified. Exact native folder/move/Combined and group-undo admission remains authoritative. No geometry/default/shared/morph changes except data explicitly removed with a folder. No binding/form restoration verification claim. Parameter GUIDs plus unique display IDs and history are identity scope; folder GUID CAS remains unavailable. Expected hash uses provisional folder IDs, actual hash uses discovered native IDs. Query result/replay exact operationId after timeout; do not blindly replan/rewrite. Cancellation only prevents bridge admission, not native commit; no asynchronous cancel. Cannot nest in transaction.execute. Destructive hint covers explicit cascading deletion.";
        return Map.of(
                "name",
                name,
                "title",
                read
                        ? "Read parameter organization"
                        : plan
                                ? "Preview parameter organization edits"
                                : result
                                        ? "Read retained parameter structure result"
                                        : "Apply parameter organization plan",
                "description",
                description,
                "inputSchema",
                object(input),
                "outputSchema",
                read ? readOutput() : plan ? planOutput() : resultOutput(),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        read || plan || result,
                        "destructiveHint",
                        !read && !plan && !result,
                        "idempotentHint",
                        true,
                        "openWorldHint",
                        false));
    }

    private static Map<String, Object> edit() {
        return Map.of(
                "oneOf",
                List.of(
                        object(Map.of("kind", constant("create_group"), "ref", string(64), "name", string(256))),
                        object(Map.of("kind", constant("rename_group"), "group", target(), "name", string(256))),
                        object(Map.of(
                                "kind",
                                constant("remove_group"),
                                "group",
                                target(),
                                "expectedGroupIds",
                                array(id(), 1, 128),
                                "expectedParameterIds",
                                array(id(), 0, 512))),
                        object(Map.of(
                                "kind",
                                constant("move_parameter"),
                                "parameterId",
                                id(),
                                "targetGroup",
                                target(),
                                "insertIndex",
                                integer(0, 640))),
                        object(Map.of("kind", constant("combine"), "parameterId", id(), "partnerId", id())),
                        object(Map.of("kind", constant("uncombine"), "parameterId", id()))));
    }

    private static Map<String, Object> target() {
        return Map.of("oneOf", List.of(object(Map.of("id", id())), object(Map.of("createdRef", string(64)))));
    }

    private static Map<String, Object> structure() {
        var child = object(Map.of("kind", Map.of("type", "string", "enum", List.of("GROUP", "PARAMETER")), "id", id()));
        var group = object(Map.of(
                "id", id(), "name", nullableString(), "parentId", nullableString(), "children", array(child, 0, 640)));
        var p = new LinkedHashMap<String, Object>();
        p.put("id", id());
        p.put("guid", id());
        p.put("index", integer(0, 511));
        p.put("name", nullableString());
        p.put("type", Map.of("type", "string", "enum", List.of("NORMAL", "BLEND_SHAPE", "UNKNOWN")));
        p.put("repeat", Map.of("type", List.of("boolean", "null")));
        p.put("combined", Map.of("type", List.of("boolean", "null")));
        for (String key : List.of("minimum", "maximum", "default")) p.put(key, Map.of("type", "number"));
        p.put("partnerId", nullableString());
        return object(Map.of("groups", array(group, 1, 128), "parameters", array(object(p), 0, 512)));
    }

    private static Map<String, Object> readOutput() {
        var binding = new LinkedHashMap<String, Object>();
        for (String key : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            binding.put(key, id());
        for (String key : List.of("historyGeneration", "historyRevision"))
            binding.put(key, Map.of("type", "integer", "minimum", 0));
        return successOrError(object(Map.of(
                "ok",
                constant(true),
                "snapshotId",
                shortId(),
                "state",
                object(binding),
                "structure",
                structure(),
                "identityScope",
                constant("PARAMETER_GUIDS_GROUP_IDS_AND_HISTORY_BINDING"),
                "snapshotTtlSeconds",
                constant(300))));
    }

    private static Map<String, Object> planOutput() {
        return successOrError(object(Map.of(
                "ok",
                constant(true),
                "planId",
                shortId(),
                "beforeHash",
                id(),
                "expectedAfterHash",
                id(),
                "expectedStructure",
                structure(),
                "effects",
                array(
                        object(Map.of(
                                "kind",
                                id(),
                                "deletedGroupIds",
                                array(id(), 0, 128),
                                "deletedParameterIds",
                                array(id(), 0, 512))),
                        1,
                        16),
                "provisionalGroups",
                array(object(Map.of("ref", string(64), "provisionalId", id())), 0, 16),
                "writeAdmission",
                constant("NOT_CHECKED"))));
    }

    private static Map<String, Object> resultOutput() {
        var schema = new LinkedHashMap<>(McpWriteResults.schema());
        @SuppressWarnings("unchecked")
        var props = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        props.put("createdGroups", array(object(Map.of("ref", string(64), "groupId", id())), 0, 16));
        props.put("actualAfterHash", nullableString());
        props.put("rollbackStateVerified", Map.of("type", "boolean"));
        schema.put("properties", props);
        schema.put("required", List.copyOf(props.keySet()));
        return schema;
    }

    private static Map<String, Object> successOrError(Map<String, Object> success) {
        return Map.of("oneOf", List.of(success, object(Map.of("ok", constant(false), "error", id()))));
    }

    private static Map<String, Object> id() {
        return string(4096);
    }

    private static Map<String, Object> shortId() {
        return string(128);
    }

    private static Map<String, Object> string(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> nullableString() {
        return Map.of("type", List.of("string", "null"), "maxLength", 4096);
    }

    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> constant(Object value) {
        return Map.of("const", value);
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
