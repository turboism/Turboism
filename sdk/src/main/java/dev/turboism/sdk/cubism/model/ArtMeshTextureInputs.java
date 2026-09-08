package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Ordered texture-input projection for one ArtMesh. */
public record ArtMeshTextureInputs(
    ArtMeshId id,
    List<TextureInputBinding> inputs,
    OptionalInt currentInputIndex
) {
    public ArtMeshTextureInputs {
        id = Objects.requireNonNull(id, "id");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        Objects.requireNonNull(currentInputIndex, "currentInputIndex");
        if (currentInputIndex.isPresent()
            && (currentInputIndex.getAsInt() < 0 || currentInputIndex.getAsInt() >= inputs.size())) {
            throw new IllegalArgumentException("currentInputIndex must address an input in this snapshot");
        }
    }

    public ArtMeshId artMeshId() {
        return id;
    }

    public OptionalInt currentIndex() {
        return currentInputIndex;
    }
}
