package dev.turboism.sdk.cubism;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record SelectionSnapshot(
    List<String> selectedObjectIds,
    Optional<String> activeParameterId,
    Optional<String> activeArtMeshId,
    Optional<String> activeDeformerId
) {
    public SelectionSnapshot {
        selectedObjectIds = List.copyOf(Objects.requireNonNull(selectedObjectIds, "selectedObjectIds"));
        activeParameterId = Objects.requireNonNull(activeParameterId, "activeParameterId");
        activeArtMeshId = Objects.requireNonNull(activeArtMeshId, "activeArtMeshId");
        activeDeformerId = Objects.requireNonNull(activeDeformerId, "activeDeformerId");
    }
}
