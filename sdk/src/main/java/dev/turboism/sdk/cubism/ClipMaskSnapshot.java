package dev.turboism.sdk.cubism;

import java.util.List;
import java.util.Objects;

/** Immutable read-only clipping configuration for one target ArtMesh. */
public record ClipMaskSnapshot(
    String targetMeshId,
    List<String> orderedMaskSourceIds,
    boolean inverted
) {
    public ClipMaskSnapshot {
        Objects.requireNonNull(targetMeshId, "targetMeshId");
        if (targetMeshId.isBlank()) {
            throw new IllegalArgumentException("targetMeshId must not be blank");
        }
        orderedMaskSourceIds = List.copyOf(orderedMaskSourceIds);
        for (String sourceId : orderedMaskSourceIds) {
            Objects.requireNonNull(sourceId, "orderedMaskSourceIds element");
            if (sourceId.isBlank()) {
                throw new IllegalArgumentException("orderedMaskSourceIds must not contain blank values");
            }
        }
    }
}
