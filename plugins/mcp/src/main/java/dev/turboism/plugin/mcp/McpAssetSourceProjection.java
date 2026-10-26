package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detached metadata only. Current source, linked candidates and inactive inputs remain distinct. */
final class McpAssetSourceProjection {
    static final int MAX_RAW = 1024,
            MAX_IMAGES = 2048,
            MAX_MESHES = 2048,
            MAX_GROUPS = 256,
            MAX_LAYERS = 8192,
            MAX_LINKS = 16384,
            MAX_ROWS = 32768,
            MAX_BYTES = 4 * 1024 * 1024,
            MAX_ROW_BYTES = 192 * 1024,
            MAX_DEPTH = 64;

    record Observation(
            String binding,
            long generation,
            long revision,
            List<Map<String, Object>> rows,
            Map<String, Object> counts,
            int bytes) {
        boolean sameData(Observation other) {
            return binding.equals(other.binding)
                    && generation == other.generation
                    && rows.equals(other.rows)
                    && counts.equals(other.counts);
        }
    }

    static Observation sources(TextureSourcesSnapshot s, TextureSourceQuery query) {
        if (!s.isAvailable()) throw new UnsupportedOperationException();
        var out = new Rows();
        size(s.rawImages().size(), MAX_RAW);
        size(s.modelImages().size(), MAX_IMAGES);
        size(s.artMeshInputs().size(), MAX_MESHES);
        var rawIds = new HashSet<String>();
        for (var raw : s.rawImages()) {
            String id = id(raw.id().value());
            unique(rawIds, id);
            out.add(raw("raw_image", raw));
        }
        var allowedImages = new HashSet<>(
                query.modelImages().stream().map(ModelImageId::value).toList());
        var meshIds = new HashSet<String>();
        for (var mesh : s.artMeshInputs()) {
            if (!query.artMeshes().contains(mesh.id())) throw new Invalid();
            unique(meshIds, id(mesh.id().value()));
            mesh.inputs().forEach(input -> input.modelImageId().ifPresent(mid -> allowedImages.add(mid.value())));
            inputs(out, mesh);
        }
        var imageIds = new HashSet<String>();
        var allowedRaws = new HashSet<String>();
        for (var image : s.modelImages()) {
            String id = id(image.id().value());
            unique(imageIds, id);
            if (!allowedImages.contains(id)) throw new Invalid();
            String current = image.currentRawImageId().map(v -> id(v.value())).orElse(null);
            if (current != null) allowedRaws.add(current);
            out.add(row(
                    "kind",
                    "model_image_source",
                    "modelImageId",
                    id,
                    "currentRawImageId",
                    current,
                    "currentSourceResolved",
                    current != null && rawIds.contains(current)));
        }
        if (!allowedRaws.containsAll(rawIds)) throw new Invalid();
        for (var id : query.artMeshes().stream().map(ArtMeshId::value).sorted().toList())
            if (!meshIds.contains(id)) out.add(row("kind", "unresolved_anchor", "anchorKind", "art_mesh", "id", id));
        for (var id :
                query.modelImages().stream().map(ModelImageId::value).sorted().toList())
            if (!imageIds.contains(id))
                out.add(row("kind", "unresolved_anchor", "anchorKind", "model_image", "id", id));
        return out.finish(s.binding(), s.generation(), s.revision(), rawIds.size(), imageIds.size(), meshIds.size(), 0);
    }

    static Observation relations(TextureRelationsSnapshot s) {
        if (!s.isAvailable()) throw new UnsupportedOperationException();
        var out = new Rows();
        size(s.rawImages().size(), MAX_RAW);
        size(s.modelImages().size(), MAX_IMAGES);
        size(s.artMeshInputs().size(), MAX_MESHES);
        size(s.groups().size(), MAX_GROUPS);
        var rawIds = new HashSet<String>();
        var layerIds = new HashSet<LayerKey>();
        for (var details : s.rawImages()) {
            String rawId = id(details.id().value());
            unique(rawIds, rawId);
            var value = new LinkedHashMap<>(raw("raw_image_details", details.rawImage()));
            value.put("sourceKind", details.sourceKind().name());
            value.put("replaced", details.replaced());
            value.put(
                    "importedAt",
                    details.importedAt().map(McpAssetSourceProjection::text).orElse(null));
            value.put(
                    "sourceModifiedAt",
                    details.sourceModifiedAt()
                            .map(McpAssetSourceProjection::text)
                            .orElse(null));
            value.put("projectTreeVisible", details.projectTreeVisible().orElse(null));
            out.add(Collections.unmodifiableMap(value));
            layers(out, details.layers(), rawId, null, layerIds, 1);
        }
        var imageIds = new HashSet<String>();
        for (var image : s.modelImages()) unique(imageIds, id(image.id().value()));
        for (var image : s.modelImages()) {
            String mid = id(image.id().value());
            if (!mid.equals(id(image.modelImage().id().value()))) throw new Invalid();
            String current = image.currentRawImageId().map(v -> id(v.value())).orElse(null);
            out.add(row(
                    "kind",
                    "model_image",
                    "modelImageId",
                    mid,
                    "name",
                    text(image.modelImage().name()),
                    "width",
                    dimension(image.modelImage().width()),
                    "height",
                    dimension(image.modelImage().height()),
                    "linkedRawImageIds",
                    ids(
                            out,
                            image.linkedRawImageIds().stream()
                                    .map(RawImageId::value)
                                    .toList()),
                    "currentRawImageId",
                    current,
                    "currentSourceResolved",
                    current != null && rawIds.contains(current),
                    "usingArtMeshIds",
                    ids(
                            out,
                            image.usingArtMeshIds().stream()
                                    .map(ArtMeshId::value)
                                    .toList())));
            size(image.inputsByRawImage().size(), MAX_RAW);
            for (var entry : image.inputsByRawImage().entrySet()) {
                String rid = id(entry.getKey().value());
                out.links(entry.getValue().size());
                for (int index = 0; index < entry.getValue().size(); index++) {
                    var binding = entry.getValue().get(index);
                    if (!entry.getKey().equals(binding.rawImageId())) throw new Invalid();
                    String lid = id(binding.rawLayerId().value());
                    out.add(row(
                            "kind",
                            "layer_input",
                            "modelImageId",
                            mid,
                            "rawImageId",
                            rid,
                            "rawLayerId",
                            lid,
                            "index",
                            index,
                            "inputOrder",
                            binding.inputOrder(),
                            "transformAvailability",
                            binding.transformAvailability().name(),
                            "clippingAvailability",
                            binding.clippingAvailability().name(),
                            "rawImageResolved",
                            rawIds.contains(rid),
                            "rawLayerResolved",
                            layerIds.contains(new LayerKey(rid, lid))));
                }
            }
        }
        for (int index = 0; index < s.groups().size(); index++) {
            var group = s.groups().get(index);
            out.add(row(
                    "kind",
                    "model_image_group",
                    "groupIndex",
                    index,
                    "name",
                    text(group.groupName()),
                    "memo",
                    text(group.memo()),
                    "modelImageIds",
                    ids(
                            out,
                            group.modelImageIds().stream()
                                    .map(ModelImageId::value)
                                    .toList()),
                    "linkedRawImageIds",
                    ids(
                            out,
                            group.linkedRawImageIds().stream()
                                    .map(RawImageId::value)
                                    .toList()),
                    "projectTreeVisible",
                    group.projectTreeVisible().orElse(null)));
        }
        var meshIds = new HashSet<String>();
        for (var mesh : s.artMeshInputs()) {
            unique(meshIds, id(mesh.id().value()));
            inputs(out, mesh);
        }
        return out.finish(
                s.binding(),
                s.generation(),
                s.revision(),
                rawIds.size(),
                imageIds.size(),
                meshIds.size(),
                s.groups().size());
    }

    private static void layers(
            Rows out, List<RawLayerDetails> layers, String raw, String parent, Set<LayerKey> ids, int depth) {
        if (depth > MAX_DEPTH) throw new Budget();
        size(layers.size(), MAX_LAYERS);
        for (int i = 0; i < layers.size(); i++) {
            var layer = layers.get(i);
            String lid = id(layer.id().value());
            if (!raw.equals(id(layer.ownerRawImageId().value())) || !ids.add(new LayerKey(raw, lid)))
                throw new Invalid();
            if (++out.layers > MAX_LAYERS) throw new Budget();
            out.add(row(
                    "kind",
                    "raw_layer",
                    "rawImageId",
                    raw,
                    "rawLayerId",
                    lid,
                    "parentRawLayerId",
                    parent,
                    "index",
                    i,
                    "entryKind",
                    layer.entryKind().name(),
                    "name",
                    text(layer.name()),
                    "psdLayerId",
                    layer.psdLayerId().map(McpAssetSourceProjection::id).orElse(null),
                    "childCount",
                    layer.children().size()));
            if (!layer.children().isEmpty()) layers(out, layer.children(), raw, lid, ids, depth + 1);
        }
    }

    private static void inputs(Rows out, ArtMeshTextureInputs mesh) {
        String id = id(mesh.id().value());
        out.links(mesh.inputs().size());
        out.add(row(
                "kind",
                "art_mesh",
                "objectId",
                id,
                "inputCount",
                mesh.inputs().size(),
                "currentInputIndex",
                mesh.currentInputIndex().isPresent() ? mesh.currentInputIndex().getAsInt() : null));
        for (int index = 0; index < mesh.inputs().size(); index++) {
            var input = mesh.inputs().get(index);
            out.add(row(
                    "kind",
                    "art_mesh_input",
                    "objectId",
                    id,
                    "index",
                    index,
                    "inputKind",
                    input.kind().name(),
                    "modelImageId",
                    input.modelImageId().map(v -> id(v.value())).orElse(null),
                    "textureAtlasId",
                    input.textureAtlasId().map(v -> id(v.value())).orElse(null),
                    "resolutionState",
                    input.resolutionState().name(),
                    "current",
                    mesh.currentInputIndex().isPresent()
                            && mesh.currentInputIndex().getAsInt() == index));
        }
    }

    private static Map<String, Object> raw(String kind, RawTexture image) {
        return row(
                "kind",
                kind,
                "rawImageId",
                id(image.id().value()),
                "name",
                text(image.name()),
                "width",
                dimension(image.width()),
                "height",
                dimension(image.height()));
    }

    private static List<String> ids(Rows out, List<String> ids) {
        out.links(ids.size());
        return ids.stream().map(McpAssetSourceProjection::id).toList();
    }

    static Map<String, Object> row(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(result);
    }

    static String id(String s) {
        if (s == null || s.isBlank() || s.length() > 4096 || s.chars().anyMatch(Character::isISOControl))
            throw new Invalid();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(++i))) throw new Invalid();
            } else if (Character.isLowSurrogate(c)) throw new Invalid();
        }
        return s;
    }

    private static String text(String s) {
        if (s == null || s.length() > 4096) throw new Invalid();
        return s;
    }

    private static int dimension(int v) {
        if (v < 1) throw new Invalid();
        return v;
    }

    private static void unique(Set<String> ids, String id) {
        if (!ids.add(id)) throw new Invalid();
    }

    private static void size(int size, int max) {
        if (size > max) throw new Budget();
    }

    static int bytes(Map<String, ?> value) {
        return Json.stringify(value).getBytes(StandardCharsets.UTF_8).length;
    }

    private record LayerKey(String raw, String layer) {}

    static final class Budget extends RuntimeException {}

    static final class Invalid extends RuntimeException {}

    private static final class Rows {
        final ArrayList<Map<String, Object>> rows = new ArrayList<>();
        int bytes, layers, links;

        void add(Map<String, Object> row) {
            int n = bytes(row);
            if (n > MAX_ROW_BYTES || rows.size() >= MAX_ROWS || (long) bytes + n > MAX_BYTES) throw new Budget();
            bytes += n;
            rows.add(row);
        }

        void links(int count) {
            if (count > MAX_LINKS - links) throw new Budget();
            links += count;
        }

        Observation finish(
                String binding, long generation, long revision, int raw, int images, int meshes, int groups) {
            var counts = Map.<String, Object>of(
                    "rawImages",
                    raw,
                    "modelImages",
                    images,
                    "artMeshes",
                    meshes,
                    "groups",
                    groups,
                    "layers",
                    layers,
                    "links",
                    links);
            var stable = List.copyOf(rows);
            int encoded = bytes(Map.of(
                    "binding",
                    id(binding),
                    "generation",
                    generation,
                    "revision",
                    revision,
                    "rows",
                    stable,
                    "counts",
                    counts));
            if (encoded > MAX_BYTES) throw new Budget();
            return new Observation(binding, generation, revision, stable, counts, encoded);
        }
    }
}
