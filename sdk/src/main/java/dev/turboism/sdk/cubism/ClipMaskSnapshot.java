package dev.turboism.sdk.cubism;

import java.util.List;

public record ClipMaskSnapshot(
    String clipMaskId,
    List<String> sourceMeshIds,
    List<String> clippedMeshIds,
    boolean enabled
) {
    public ClipMaskSnapshot {
        if (clipMaskId == null || clipMaskId.isBlank()) {
            throw new IllegalArgumentException("clipMaskId must not be null or blank");
        }
        sourceMeshIds = List.copyOf(sourceMeshIds);
        clippedMeshIds = List.copyOf(clippedMeshIds);
    }
}
