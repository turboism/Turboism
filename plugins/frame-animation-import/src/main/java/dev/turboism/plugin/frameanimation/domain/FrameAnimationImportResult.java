package dev.turboism.plugin.frameanimation.domain;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.PartId;
import java.util.List;
import java.util.Objects;

/**
 * Result of a completed frame animation import operation.
 */
public record FrameAnimationImportResult(
        PartId partId, ParameterId parameterId, List<ArtMeshId> createdMeshIds, int frameCount) {

    public FrameAnimationImportResult {
        Objects.requireNonNull(partId, "partId");
        Objects.requireNonNull(parameterId, "parameterId");
        createdMeshIds = List.copyOf(Objects.requireNonNull(createdMeshIds, "createdMeshIds"));
        if (frameCount < 0) {
            throw new IllegalArgumentException("frameCount must be non-negative");
        }
    }
}
