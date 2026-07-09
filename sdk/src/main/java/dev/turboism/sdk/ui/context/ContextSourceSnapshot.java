package dev.turboism.sdk.ui.context;

import java.util.Optional;

/**
 * Typed source context for a host context-menu or UI invocation.
 *
 * <p>It intentionally exposes only Turboism-owned IDs and semantic context
 * kinds, never native menu/source objects.</p>
 */
public record ContextSourceSnapshot(
    String sourceId,
    String contextKind,
    Optional<String> modelObjectId,
    Optional<String> parameterId,
    Optional<String> artMeshId,
    Optional<String> deformerId
) {
    public ContextSourceSnapshot {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be null or blank");
        }
        if (contextKind == null || contextKind.isBlank()) {
            throw new IllegalArgumentException("contextKind must not be null or blank");
        }
        modelObjectId = java.util.Objects.requireNonNull(modelObjectId, "modelObjectId");
        parameterId = java.util.Objects.requireNonNull(parameterId, "parameterId");
        artMeshId = java.util.Objects.requireNonNull(artMeshId, "artMeshId");
        deformerId = java.util.Objects.requireNonNull(deformerId, "deformerId");
    }
}
