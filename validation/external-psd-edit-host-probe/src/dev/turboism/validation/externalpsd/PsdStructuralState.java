package dev.turboism.validation.externalpsd;

import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.RawLayerDetails;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Validation-only structural observations for independent native/SDK replacement controls. */
final class PsdStructuralState {
    private PsdStructuralState() { }

    record Basis(Set<String> rawIds, Set<String> imageIds, Map<String, String> meshGuids) {
        Basis {
            rawIds = Set.copyOf(rawIds);
            imageIds = Set.copyOf(imageIds);
            meshGuids = Map.copyOf(meshGuids);
        }
    }

    static Basis basis(final CubismModel model, final TextureRelationsSnapshot relations) {
        requireEdt();
        requireRelations(relations);
        final Map<String, String> guids = new TreeMap<>();
        final Set<String> uniqueGuids = new HashSet<>();
        for (final var mesh : model.drawables().all()) {
            putUnique(guids, mesh.id().value(), mesh.guid());
            if (!uniqueGuids.add(mesh.guid())) throw new IllegalStateException("duplicate ArtMesh GUID");
        }
        final Basis basis = new Basis(relations.rawImages().stream().map(raw -> raw.id().value())
            .collect(java.util.stream.Collectors.toSet()),
            relations.modelImages().stream().map(image -> image.id().value())
                .collect(java.util.stream.Collectors.toSet()), guids);
        relations(relations, basis); // Reject duplicate or unresolved relation identities at baseline.
        return basis;
    }

    /** Caller must bind document/model/window and invoke this whole observation on EDT. */
    static Map<String, String> capture(final CubismModel model,
        final TextureRelationsSnapshot relations, final Basis basis,
        final Map<String, OfficialPsdReplacementBaseline.AuthoringColors> colors) {
        requireEdt();
        final Map<String, String> values = new TreeMap<>(relations(relations, basis));
        final var canvas = model.canvas();
        values.put("canvas", List.of(canvas.widthPixels(), canvas.heightPixels(),
            canvas.originXPixels(), canvas.originYPixels(), canvas.pixelsPerUnit()).toString());
        final Set<String> meshIds = new HashSet<>();
        for (final var mesh : model.drawables().all()) {
            final String id = mesh.id().value();
            if (!meshIds.add(id)) throw new IllegalStateException("duplicate ArtMesh ID");
            final String key = "mesh." + token(id) + ".";
            values.put(key + "guidRole", mesh.guid().equals(basis.meshGuids().get(id))
                ? "preserved:" + mesh.guid() : "new-guid");
            values.put(key + "name", mesh.name());
            values.put(key + "geometry", mesh.geometry().toString());
            values.put(key + "part", mesh.parentPartId().toString());
            values.put(key + "deformer", mesh.parentDeformerId().toString());
            values.put(key + "visible", Boolean.toString(mesh.visible()));
            values.put(key + "locked", Boolean.toString(mesh.locked()));
            values.put(key + "opacity", Float.toHexString(mesh.getOpacity()));
            values.put(key + "order", Integer.toString(mesh.drawOrder()));
            values.put(key + "masks", mesh.maskIds().toString());
            values.put(key + "invertedMask", Boolean.toString(mesh.invertedMask()));
            values.put(key + "culling", Boolean.toString(mesh.culling()));
            final var color = colors.get(id);
            if (color == null || !mesh.guid().equals(color.guid())) throw new IllegalStateException(
                "authoring colors do not match ArtMesh identity");
            values.put(key + "multiply", color.multiply().toString());
            values.put(key + "screen", color.screen().toString());
        }
        values.put("mesh.count", Integer.toString(meshIds.size()));
        if (!meshIds.equals(colors.keySet())) throw new IllegalStateException(
            "authoring color coverage differs from ArtMesh metadata");
        if (!meshIds.equals(relations.artMeshInputs().stream().map(mesh -> mesh.id().value())
            .collect(java.util.stream.Collectors.toSet()))) throw new IllegalStateException(
                "ArtMesh metadata and relation coverage differ");
        // Keep this observation's exact limits explicit; these are not guessed zero values.
        values.put("scope", "current-keyform geometry, authoring state, public texture relations");
        values.put("colors.scope", "official Editor current-keyform RGBA; not evaluated Core colors");
        values.put("rawInputTransformAndClipping", "SDK_DETAILS_UNAVAILABLE");
        values.put("excluded", "observation revision/binding, import/source timestamps, generated GUID values");
        return Map.copyOf(values);
    }

    static Map<String, String> relations(final TextureRelationsSnapshot relations,
        final Basis basis) {
        requireRelations(relations);
        final Map<String, String> values = new TreeMap<>();
        final Map<String, String> rawKeys = new HashMap<>();
        final Map<String, String> layerKeys = new HashMap<>();
        int incoming = 0;
        for (final var raw : relations.rawImages()) {
            final String id = raw.id().value();
            final String key = basis.rawIds().contains(id) ? "original:" + token(id) : "incoming";
            if (!basis.rawIds().contains(id) && ++incoming > 1) throw new IllegalStateException(
                "structural control has multiple incoming raw identities");
            putUnique(rawKeys, id, key);
            values.put("raw." + key + ".name", raw.rawTexture().name());
            values.put("raw." + key + ".size", raw.rawTexture().width() + "x" + raw.rawTexture().height());
            values.put("raw." + key + ".replaced", Boolean.toString(raw.replaced()));
            values.put("raw." + key + ".sourceKind", raw.sourceKind().name());
            values.put("raw." + key + ".treeVisible", raw.projectTreeVisible().toString());
            layers(values, layerKeys, id, key, "", raw.layers(), basis.rawIds().contains(id));
        }
        final Map<String, String> imageKeys = new HashMap<>();
        final Set<String> uniqueImages = new HashSet<>();
        for (final var image : relations.modelImages()) {
            final List<String> bindings = new ArrayList<>();
            for (final var entry : image.inputsByRawImage().entrySet()) {
                final String raw = required(rawKeys, entry.getKey().value());
                final List<String> ordered = new ArrayList<>();
                for (final var binding : entry.getValue()) {
                    ordered.add(required(layerKeys,
                        binding.rawImageId().value() + "/" + binding.rawLayerId().value())
                        + "@" + binding.inputOrder() + ":" + binding.transformAvailability()
                        + ":" + binding.clippingAvailability());
                }
                bindings.add(token(raw) + pack(ordered));
            }
            java.util.Collections.sort(bindings);
            final String id = image.id().value();
            if (!id.equals(image.modelImage().id().value())) throw new IllegalStateException(
                "model-image metadata identity mismatch");
            if (!basis.imageIds().contains(id) && image.inputsByRawImage().values().stream()
                .allMatch(List::isEmpty)) throw new IllegalStateException(
                    "new model image cannot be matched without layer inputs");
            final String key = basis.imageIds().contains(id) ? "original:" + token(id)
                : "new:" + pack(bindings);
            if (!uniqueImages.add(key)) throw new IllegalStateException(
                "new model-image identity is structurally ambiguous");
            putUnique(imageKeys, id, key);
            final String prefix = "image." + key + ".";
            values.put(prefix + "name", image.modelImage().name());
            values.put(prefix + "size", image.modelImage().width() + "x" + image.modelImage().height());
            values.put(prefix + "bindings", pack(bindings));
            values.put(prefix + "current", image.currentRawImageId()
                .map(raw -> required(rawKeys, raw.value())).orElse("absent"));
            values.put(prefix + "linked", image.linkedRawImageIds().stream()
                .map(raw -> required(rawKeys, raw.value())).sorted()
                .collect(java.util.stream.Collectors.collectingAndThen(
                    java.util.stream.Collectors.toList(), PsdStructuralState::pack)));
            values.put(prefix + "users", image.usingArtMeshIds().stream()
                .map(mesh -> mesh.value()).sorted().toList().toString());
        }
        for (final var mesh : relations.artMeshInputs()) {
            final List<String> inputs = mesh.inputs().stream().map(input -> input.kind() + ":"
                + input.resolutionState() + ":" + input.modelImageId()
                    .map(id -> required(imageKeys, id.value())).orElse("absent")
                + ":" + input.textureAtlasId()).toList();
            putUnique(values, "inputs." + token(mesh.id().value()), mesh.currentInputIndex() + ":" + pack(inputs));
        }
        final List<String> groups = new ArrayList<>();
        for (final var group : relations.groups()) {
            groups.add(pack(List.of(group.groupName(), group.memo(), group.projectTreeVisible().toString(),
                group.modelImageIds().stream().map(id -> required(imageKeys, id.value()))
                    .map(PsdStructuralState::token).collect(java.util.stream.Collectors.joining()),
                group.linkedRawImageIds().stream().map(id -> required(rawKeys, id.value()))
                    .map(PsdStructuralState::token).collect(java.util.stream.Collectors.joining()))));
        }
        values.put("groups", pack(groups));
        values.put("raw.count", Integer.toString(rawKeys.size()));
        values.put("image.count", Integer.toString(imageKeys.size()));
        return Map.copyOf(values);
    }

    private static void layers(final Map<String, String> values, final Map<String, String> layerKeys,
        final String raw, final String rawKey, final String parent, final List<RawLayerDetails> layers,
        final boolean originalRaw) {
        for (int index = 0; index < layers.size(); index++) {
            final var layer = layers.get(index);
            if (!raw.equals(layer.ownerRawImageId().value())) throw new IllegalStateException(
                "layer belongs to another raw");
            final String key = parent + "/" + index;
            putUnique(layerKeys, raw + "/" + layer.id().value(), key);
            values.put("raw." + rawKey + ".layer" + key, pack(List.of(layer.entryKind().name(),
                layer.name(), layer.psdLayerId().toString(),
                originalRaw ? layer.id().value() : "generated-layer-guid")));
            layers(values, layerKeys, raw, rawKey, key, layer.children(), originalRaw);
        }
    }

    private static void requireRelations(final TextureRelationsSnapshot relations) {
        if (relations == null || !relations.isAvailable()) throw new IllegalStateException(
            "structural texture relations unavailable");
    }

    private static void requireEdt() {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException(
            "structural observation requires EDT");
    }

    private static String token(final String value) { return value.length() + ":" + value; }

    private static String pack(final List<String> values) {
        return values.stream().map(PsdStructuralState::token)
            .collect(java.util.stream.Collectors.joining());
    }

    private static String required(final Map<String, String> map, final String key) {
        final String value = map.get(key);
        if (value == null) throw new IllegalStateException("unresolved structural identity: " + key);
        return value;
    }

    private static void putUnique(final Map<String, String> map, final String key, final String value) {
        if (map.putIfAbsent(key, value) != null) throw new IllegalStateException(
            "duplicated structural identity: " + key);
    }
}
