package dev.turboism.adapter.cubism;

import java.util.Objects;

/** Runtime-private target identity for the verified native label-color seam. */
public record NativeLabelColorTarget(Palette palette, String objectId) {

    public enum Palette {
        PART,
        DEFORMER,
        PARAMETER_GROUP
    }

    public NativeLabelColorTarget {
        palette = Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(objectId, "objectId");
        if (objectId.isBlank()) throw new IllegalArgumentException("objectId must not be blank");
    }
}
