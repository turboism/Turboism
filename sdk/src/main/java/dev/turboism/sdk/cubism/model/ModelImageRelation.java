package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable connections from one model image to raw images, layers, and meshes. */
public record ModelImageRelation(
    ModelImageId id,
    ModelImageEntry modelImage,
    List<RawImageId> linkedRawImageIds,
    Optional<RawImageId> currentRawImageId,
    Map<RawImageId, List<RawLayerBinding>> inputsByRawImage,
    List<ArtMeshId> usingArtMeshIds
) {
    public ModelImageRelation {
        id = Objects.requireNonNull(id, "id");
        modelImage = Objects.requireNonNull(modelImage, "modelImage");
        linkedRawImageIds = List.copyOf(Objects.requireNonNull(linkedRawImageIds, "linkedRawImageIds"));
        currentRawImageId = Objects.requireNonNull(currentRawImageId, "currentRawImageId");
        inputsByRawImage = immutableBindings(inputsByRawImage);
        usingArtMeshIds = List.copyOf(Objects.requireNonNull(usingArtMeshIds, "usingArtMeshIds"));
        for (final Map.Entry<RawImageId, List<RawLayerBinding>> entry : inputsByRawImage.entrySet()) {
            for (final RawLayerBinding binding : entry.getValue()) {
                if (!entry.getKey().equals(binding.rawImageId())) {
                    throw new IllegalArgumentException("layer binding raw image key does not match its value");
                }
            }
        }
    }

    /** Returns the model-image identity; alias for {@link #id()}. */
    public ModelImageId modelImageId() {
        return id;
    }

    /** Returns the model-image metadata captured in this relation. */
    public ModelImageEntry entry() {
        return modelImage;
    }

    /** Returns immutable ordered layer inputs grouped by raw-image identity. */
    public Map<RawImageId, List<RawLayerBinding>> layerInputsByRawImage() {
        return inputsByRawImage;
    }

    /** Returns the captured users of this model image, which may be shared. */
    public List<ArtMeshId> artMeshIds() {
        return usingArtMeshIds;
    }

    private static Map<RawImageId, List<RawLayerBinding>> immutableBindings(
        final Map<RawImageId, List<RawLayerBinding>> source
    ) {
        Objects.requireNonNull(source, "inputsByRawImage");
        final Map<RawImageId, List<RawLayerBinding>> copy = new LinkedHashMap<>();
        for (final Map.Entry<RawImageId, List<RawLayerBinding>> entry : source.entrySet()) {
            copy.put(
                Objects.requireNonNull(entry.getKey(), "inputsByRawImage key"),
                List.copyOf(Objects.requireNonNull(entry.getValue(), "inputsByRawImage value"))
            );
        }
        return Collections.unmodifiableMap(copy);
    }
}
