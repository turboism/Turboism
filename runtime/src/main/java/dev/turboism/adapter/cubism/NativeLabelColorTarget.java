package dev.turboism.adapter.cubism;

import java.util.Objects;

/** Runtime-private target identity for the verified native label-color seam. */
public record NativeLabelColorTarget(Palette palette, String objectId) {

    /** The host palette an {@link NativeLabelColorTarget#objectId()} belongs to. */
    public enum Palette {
        PART,
        DEFORMER,
        ART_MESH,
        PARAMETER_GROUP
    }

    public NativeLabelColorTarget {
        palette = Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(objectId, "objectId");
        if (objectId.isBlank()) throw new IllegalArgumentException("objectId must not be blank");
    }
}
