package dev.turboism.sdk.cubism;

import java.util.List;

public record PsdDocumentSnapshot(
    String documentId,
    String relativePath,
    List<PsdLayerSnapshot> layers
) {
    public PsdDocumentSnapshot {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be null or blank");
        }
        if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/") || relativePath.contains("..")) {
            throw new IllegalArgumentException("relativePath must be relative and must not contain parent segments");
        }
        layers = List.copyOf(layers);
    }

    public record PsdLayerSnapshot(String layerId, String name, boolean visible) {
        public PsdLayerSnapshot {
            if (layerId == null || layerId.isBlank()) {
                throw new IllegalArgumentException("layerId must not be null or blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be null or blank");
            }
        }
    }
}
