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

    /** Returns the ArtMesh identity; alias for {@link #id()}. */
    public ArtMeshId artMeshId() {
        return id;
    }

    /** Returns the zero-based current input index, or empty when unknown. */
    public OptionalInt currentIndex() {
        return currentInputIndex;
    }
}
