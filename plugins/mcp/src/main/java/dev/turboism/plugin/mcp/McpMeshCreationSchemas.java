package dev.turboism.plugin.mcp;

import java.util.*;

final class McpMeshCreationSchemas {
    static Map<String, Object> definition(String name) {
        boolean tri = name.equals(McpMeshCreationDomain.TRIANGULATE),
                plan = name.equals(McpMeshCreationDomain.PLAN),
                result = name.equals(McpMeshCreationDomain.RESULT);
        var input = new LinkedHashMap<String, Object>();
        if (!result) {
            input.put("documentId", id());
            input.put("modelId", id());
        }
        if (tri || plan) input.put("coordinateSpace", constant("CANVAS"));
        if (tri) input.put("geometry", geometry(false));
        if (plan)
            input.put(
                    "meshes",
                    array(
                            object(Map.of(
                                    "name",
                                    string(256),
                                    "parentPartId",
                                    id(),
                                    "modelImageId",
                                    nullable(),
                                    "sourceGeometry",
                                    geometry(true),
                                    "defaultPositions",
                                    points())),
                            1,
                            8));
        if (!tri && !plan) {
            input.put("operationId", string(128));
            if (!result) input.put("planId", string(128));
        }
        String description = tri
                ? "Detached native source triangulation in CANVAS coordinates. May merge/reorder positions; returns actual positions and indices, never inferred UV mapping. Does not adopt topology or mutate model/pose/selection. Input UVs are explicit normalized values but no output UV mapping is provided. Max4096 points/24576 indices/240KiB output."
                : plan
                        ? "Plan 1..8 NEW ArtMeshes with explicit source positions/UVs/triangles and separate default positions. Existing parentPartId required; ancestors must be visible/unlocked. Optional existing modelImageId; null deliberately creates an unbound mesh for later authorized PNG registration. CANVAS xy, normalized UV[0,1]. No screenshot/Core coordinate conversion. Reject degenerate/duplicate faces. Max4096 vertices/24576 indices per mesh, total16384/65536,768KiB request. Captures existing ArtMesh native source/stored forms/GUID/parents/lock/image bindings, Part ID/name/parent/index/visibility/lock and image metadata, plus history. Part identity is declared fields/history, not native GUID. Max512 existing meshes/512 Parts/128 image groups/1024 images/262144 source+stored points/4MiB encoded state; native snapshot allocation precedes budget checks. Max16 plans,5min TTL, roughly64MiB encoded state plus native DTO overhead. Does not test write admission or create objects."
                        : result
                                ? "Read retained session-local creation result with zero host reads. Max256 operation identities, no eviction; no restart recovery. Missing means OUTCOME_UNKNOWN. All rows are observations and may have rolled back; verified=true means source/form readback succeeded at that time, not that the ID remains live. Query/replay same operation after timeout; never blindly create again."
                                : "Create source/default ArtMesh batch and optionally bind registered images in ONE owned SDK authoring transaction. Full declared state/history CAS before and inside transaction; exact source/default/UV/topology/GUID/form/parent/binding readback and existing scoped state preservation. Never edits existing default/shared forms. Exact native host creation/binding/Undo admission remains authoritative. Post-commit readback failure is APPLIED_WITH_READBACK_WARNING; unverifiable rollback or lost response is OUTCOME_UNKNOWN. No render, pixel visibility, disk persistence or rig quality claim. Cannot nest in transaction.execute. Bridge cancellation only prevents admission; it does not cancel native commit. Result expectedAfterHash is the requested payload hash, not a predicted native-state hash.";
        return Map.of(
                "name",
                name,
                "title",
                tri
                        ? "Preview source triangulation"
                        : plan
                                ? "Plan new source meshes"
                                : result ? "Read mesh creation result" : "Create planned source meshes",
                "description",
                description,
                "inputSchema",
                object(input),
                "outputSchema",
                tri ? triOutput() : plan ? planOutput() : resultOutput(),
                "annotations",
                Map.of(
                        "readOnlyHint",
                        tri || plan || result,
                        "destructiveHint",
                        false,
                        "idempotentHint",
                        true,
                        "openWorldHint",
                        false));
    }

    private static Map<String, Object> binding() {
        var p = new LinkedHashMap<String, Object>();
        for (String k : List.of("documentId", "modelId", "historyDocumentBinding", "historyManagerBinding"))
            p.put(k, id());
        for (String k : List.of("historyGeneration", "historyRevision"))
            p.put(k, Map.of("type", "integer", "minimum", 0));
        return object(p);
    }

    private static Map<String, Object> triOutput() {
        return success(object(Map.of(
                "ok",
                constant(true),
                "state",
                binding(),
                "coordinateSpace",
                constant("CANVAS"),
                "positions",
                points(),
                "triangleIndices",
                indices(true),
                "positionsUnchanged",
                Map.of("type", "boolean"),
                "uvMapping",
                constant("NOT_RETURNED"),
                "modelMutation",
                constant("NONE"))));
    }

    private static Map<String, Object> planOutput() {
        var p = new LinkedHashMap<String, Object>();
        p.put("ok", constant(true));
        p.put("planId", string(128));
        p.put("beforeHash", id());
        p.put("requestHash", id());
        p.put("state", binding());
        p.put(
                "meshes",
                array(
                        object(Map.of(
                                "name",
                                string(256),
                                "parentPartId",
                                id(),
                                "modelImageId",
                                nullable(),
                                "vertexCount",
                                integer(3, 4096),
                                "triangleCount",
                                integer(1, 8192),
                                "sourceHash",
                                id(),
                                "defaultHash",
                                id())),
                        1,
                        8));
        p.put("writeAdmission", constant("NOT_CHECKED"));
        p.put("coordinateSpace", constant("CANVAS"));
        p.put("uvSpace", constant("NORMALIZED_TEXTURE"));
        p.put("scope", constant("EXISTING_ARTMESH_AUTHORING_PARENTS_BINDINGS_PARTS_IMAGE_METADATA"));
        return success(object(p));
    }

    private static Map<String, Object> resultOutput() {
        var schema = new LinkedHashMap<>(McpWriteResults.schema());
        @SuppressWarnings("unchecked")
        var props = new LinkedHashMap<>((Map<String, Object>) schema.get("properties"));
        var observed = object(Map.of("objectId", id(), "requestIndex", integer(0, 7), "verified", constant(false)));
        var v = new LinkedHashMap<String, Object>();
        v.put("objectId", id());
        v.put("requestIndex", integer(0, 7));
        v.put("verified", constant(true));
        for (String k : List.of("sourceGuid", "parentPartId", "formGuid", "sourceHash", "defaultHash")) v.put(k, id());
        v.put("modelImageId", nullable());
        v.put("imageBound", Map.of("type", "boolean"));
        v.put("defaultDrawOrder", Map.of("type", "integer"));
        v.put("defaultOpacity", Map.of("type", List.of("number", "null")));
        props.put("createdMeshes", array(Map.of("oneOf", List.of(observed, object(v))), 0, 8));
        props.put("rollbackStateVerified", Map.of("type", "boolean"));
        props.put("renderVerified", constant(false));
        schema.put("properties", props);
        schema.put("required", List.copyOf(props.keySet()));
        return schema;
    }

    private static Map<String, Object> success(Map<String, Object> s) {
        return Map.of("oneOf", List.of(s, object(Map.of("ok", constant(false), "error", id()))));
    }

    private static Map<String, Object> geometry(boolean faces) {
        return object(Map.of("positions", points(), "uvs", points(), "triangleIndices", indices(faces)));
    }

    private static Map<String, Object> points() {
        return array(
                object(Map.of(
                        "x",
                        Map.of("type", "number", "minimum", -1e7, "maximum", 1e7),
                        "y",
                        Map.of("type", "number", "minimum", -1e7, "maximum", 1e7))),
                3,
                4096);
    }

    private static Map<String, Object> indices(boolean faces) {
        return array(integer(0, 4095), faces ? 3 : 0, 24576);
    }

    private static Map<String, Object> id() {
        return string(4096);
    }

    private static Map<String, Object> string(int max) {
        return Map.of("type", "string", "minLength", 1, "maxLength", max);
    }

    private static Map<String, Object> nullable() {
        return Map.of("type", List.of("string", "null"), "minLength", 1, "maxLength", 4096);
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

    private static Map<String, Object> object(Map<String, Object> p) {
        return Map.of(
                "type", "object", "properties", p, "required", List.copyOf(p.keySet()), "additionalProperties", false);
    }
}
