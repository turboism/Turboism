package dev.turboism.sdk.cubism;

import java.util.List;

/**
 * Immutable snapshot of a layered image (PSD) resource belonging to the project.
 *
 * <p>This describes the layer structure only; no pixel data is carried. The path is validated to
 * be contained — no leading slash, no {@code ..} segment — so it can never address a file outside
 * the project directory.</p>
 *
 * @param documentId stable identifier of the PSD resource; must not be null or blank
 * @param relativePath path of the PSD file relative to the project directory; must be relative and
 *     free of parent segments
 * @param layers unmodifiable copy of the document's layers, in host order
 * @throws IllegalArgumentException if {@code documentId} is null or blank, or {@code relativePath}
 *     is null, blank, absolute, or contains {@code ..}
 * @throws NullPointerException if {@code layers} is null
 */
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

    /**
     * One layer of the layered image, flattened out of the PSD's group hierarchy.
     *
     * @param layerId stable identifier of the layer; must not be null or blank
     * @param name layer name as authored in the source PSD; must not be null or blank
     * @param visible whether the layer's visibility flag is set in the source document
     * @throws IllegalArgumentException if {@code layerId} or {@code name} is null or blank
     */
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
