package dev.turboism.plugin.mcp;

import java.util.*;

/** Closed path-free metadata contracts. PSD IDs are not raw-layer IDs or file grants. */
final class McpAssetSourceSchemas {
    static Map<String, Object> definition(String name) {
        boolean scoped = name.equals(McpAssetSourceDomain.SOURCES);
        var input = new LinkedHashMap<String, Object>();
        input.put("documentId", id());
        input.put("modelId", id());
        if (scoped) input.put("query", object(Map.of("artMeshIds", array(id(), 64), "modelImageIds", array(id(), 64))));
        input.put("snapshotId", string(1, 128));
        input.put("offset", integer(0, Integer.MAX_VALUE));
        input.put("limit", integer(1, 256));
        var required = new ArrayList<>(List.of("documentId", "modelId"));
        if (scoped) required.add("query");
        var inputSchema = new LinkedHashMap<>(object(input));
        inputSchema.put("required", required);
        var state = object(Map.of(
                "documentId",
                id(),
                "modelId",
                id(),
                "historyGeneration",
                integer(0, Long.MAX_VALUE),
                "historyRevision",
                integer(0, Long.MAX_VALUE),
                "historyDocumentBinding",
                Map.of("type", "string"),
                "historyManagerBinding",
                Map.of("type", "string")));
        var observation = object(Map.of(
                "binding",
                id(),
                "generation",
                integer(0, Long.MAX_VALUE),
                "revision",
                integer(0, Long.MAX_VALUE),
                "revisionKind",
                Map.of("const", "OBSERVATION_SEQUENCE"),
                "historical",
                Map.of("const", true)));
        var counts = object(Map.of(
                "rawImages",
                integer(0, 1024),
                "modelImages",
                integer(0, 2048),
                "artMeshes",
                integer(0, 2048),
                "groups",
                integer(0, 256),
                "layers",
                integer(0, 8192),
                "links",
                integer(0, 16384)));
        var success = object(properties(
                "ok",
                Map.of("const", true),
                "snapshotId",
                string(1, 128),
                "projection",
                Map.of("const", scoped ? "SCOPED_SOURCES" : "RELATIONS"),
                "queryFingerprint",
                string(64, 64),
                "state",
                state,
                "observation",
                observation,
                "counts",
                counts,
                "rows",
                array(rows(scoped), 256),
                "offset",
                integer(0, 32768),
                "totalRows",
                integer(0, 32768),
                "nextOffset",
                Map.of("oneOf", List.of(integer(0, 32768), Map.of("type", "null")))));
        return Map.of(
                "name",
                name,
                "title",
                scoped ? "Read scoped texture sources" : "Read texture source and layer relations",
                "description",
                (scoped
                                ? "Resolve only explicit ArtMesh/model-image anchors; empty query reads binding metadata only, never the full graph. Missing anchors emit unresolved_anchor, missing current raw metadata remains unresolved; no candidate fallback. Source observations include ordered recorded mesh inputs and exact nullable current index. ATLAS/unknown/unavailable inputs remain distinct; an empty input list is not proof of an untextured mesh. Max64 unique IDs per anchor kind. Two bounded typed reads must agree on projected data and native binding/generation; observation revision may advance."
                                : "Read bounded raw image/layer trees, model-image current sources versus linked candidates, ordered raw-layer inputs, groups and recorded mesh texture inputs. RawLayerId is scoped by RawImageId; numeric PSD layer IDs are separate optional metadata, not global joins or PSD-document identity. sourceKind=PSD only reflects verified SDK evidence, UNKNOWN is preserved. usingArtMeshIds may include inactive inputs; not a current-source selector. Missing raw/layer references remain explicitly unresolved. Group index is observation-local, no fabricated GUID. No file paths, grants, pixels, transform matrices, actual clipping values or PSD document import/export.")
                        + " First page uses offset=0 without snapshotId; subsequent pages repeat the same query and session snapshotId. limit=1..256 (default128), max240KiB/page. Immutable historical rows are retained for5min without renewal, max16 snapshots/16MiB encoded total and4MiB each,32768 rows,1024 raw images,2048 images/meshes,256 groups,8192 layers/depth64,16384 listed links. Native allocation precedes MCP checks. Two captures compare projected metadata, not a whole-model atomic snapshot or proof of freshness; native revision is an observation sequence, not history/CAS. Cached pages check active document/model/history binding but do not reread the native graph or certify current contents. Not a write snapshot, file authorization, semantic/left-right/occlusion map, pixel/visual acceptance or permission to edit assets.",
                "inputSchema",
                inputSchema,
                "outputSchema",
                Map.of(
                        "oneOf",
                        List.of(success, object(Map.of("ok", Map.of("const", false), "error", string(1, 128))))),
                "annotations",
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true, "openWorldHint", false));
    }

    private static Map<String, Object> rows(boolean scoped) {
        var alternatives = new ArrayList<Object>();
        alternatives.add(row(
                "art_mesh",
                Map.of(
                        "objectId",
                        id(),
                        "inputCount",
                        integer(0, 16384),
                        "currentInputIndex",
                        nullable(integer(0, 16383)))));
        alternatives.add(row(
                "art_mesh_input",
                Map.of(
                        "objectId",
                        id(),
                        "index",
                        integer(0, 16383),
                        "inputKind",
                        Map.of("enum", List.of("MODEL_IMAGE", "ATLAS", "UNKNOWN")),
                        "modelImageId",
                        nullable(id()),
                        "textureAtlasId",
                        nullable(id()),
                        "resolutionState",
                        Map.of("enum", List.of("RESOLVED", "UNKNOWN", "UNAVAILABLE")),
                        "current",
                        Map.of("type", "boolean"))));
        if (scoped) {
            alternatives.add(row("raw_image", raw()));
            alternatives.add(row(
                    "model_image_source",
                    Map.of(
                            "modelImageId",
                            id(),
                            "currentRawImageId",
                            nullable(id()),
                            "currentSourceResolved",
                            Map.of("type", "boolean"))));
            alternatives.add(row(
                    "unresolved_anchor",
                    Map.of("anchorKind", Map.of("enum", List.of("art_mesh", "model_image")), "id", id())));
        } else {
            var details = new LinkedHashMap<>(raw());
            details.put("sourceKind", Map.of("enum", List.of("PSD", "UNKNOWN")));
            details.put("replaced", Map.of("type", "boolean"));
            details.put("importedAt", nullable(string(0, 4096)));
            details.put("sourceModifiedAt", nullable(string(0, 4096)));
            details.put("projectTreeVisible", nullable(Map.of("type", "boolean")));
            alternatives.add(row("raw_image_details", details));
            alternatives.add(row(
                    "raw_layer",
                    Map.of(
                            "rawImageId",
                            id(),
                            "rawLayerId",
                            id(),
                            "parentRawLayerId",
                            nullable(id()),
                            "index",
                            integer(0, 8191),
                            "entryKind",
                            Map.of("enum", List.of("PIXEL", "GROUP")),
                            "name",
                            string(0, 4096),
                            "psdLayerId",
                            nullable(id()),
                            "childCount",
                            integer(0, 8192))));
            alternatives.add(row(
                    "model_image",
                    Map.of(
                            "modelImageId",
                            id(),
                            "name",
                            string(0, 4096),
                            "width",
                            integer(1, Integer.MAX_VALUE),
                            "height",
                            integer(1, Integer.MAX_VALUE),
                            "linkedRawImageIds",
                            array(id(), 16384),
                            "currentRawImageId",
                            nullable(id()),
                            "currentSourceResolved",
                            Map.of("type", "boolean"),
                            "usingArtMeshIds",
                            array(id(), 16384))));
            alternatives.add(row(
                    "layer_input",
                    Map.of(
                            "modelImageId",
                            id(),
                            "rawImageId",
                            id(),
                            "rawLayerId",
                            id(),
                            "index",
                            integer(0, 16383),
                            "inputOrder",
                            integer(0, Integer.MAX_VALUE),
                            "transformAvailability",
                            availability(),
                            "clippingAvailability",
                            availability(),
                            "rawImageResolved",
                            Map.of("type", "boolean"),
                            "rawLayerResolved",
                            Map.of("type", "boolean"))));
            alternatives.add(row(
                    "model_image_group",
                    Map.of(
                            "groupIndex",
                            integer(0, 255),
                            "name",
                            string(0, 4096),
                            "memo",
                            string(0, 4096),
                            "modelImageIds",
                            array(id(), 16384),
                            "linkedRawImageIds",
                            array(id(), 16384),
                            "projectTreeVisible",
                            nullable(Map.of("type", "boolean")))));
        }
        return Map.of("oneOf", alternatives);
    }

    private static Map<String, Object> properties(Object... pairs) {
        var fields = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], pairs[i + 1]);
        return fields;
    }

    private static Map<String, Object> availability() {
        return Map.of("enum", List.of("AVAILABLE", "UNKNOWN", "UNAVAILABLE"));
    }

    private static Map<String, Object> raw() {
        return Map.of(
                "rawImageId",
                id(),
                "name",
                string(0, 4096),
                "width",
                integer(1, Integer.MAX_VALUE),
                "height",
                integer(1, Integer.MAX_VALUE));
    }

    private static Map<String, Object> row(String kind, Map<String, Object> fields) {
        var all = new LinkedHashMap<>(fields);
        all.put("kind", Map.of("const", kind));
        return object(all);
    }

    private static Map<String, Object> nullable(Object schema) {
        return Map.of("oneOf", List.of(schema, Map.of("type", "null")));
    }

    private static Map<String, Object> id() {
        return string(1, 4096);
    }

    private static Map<String, Object> string(int min, int max) {
        return Map.of("type", "string", "minLength", min, "maxLength", max);
    }

    private static Map<String, Object> integer(long min, long max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static Map<String, Object> array(Object schema, int max) {
        return Map.of("type", "array", "maxItems", max, "items", schema);
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
