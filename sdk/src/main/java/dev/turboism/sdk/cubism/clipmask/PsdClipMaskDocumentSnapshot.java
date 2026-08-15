package dev.turboism.sdk.cubism.clipmask;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable PSD resource snapshot with the clipping relationship data needed
 * for clip-mask planning, associated with this Editor model.
 */
@PreviewApi
public record PsdClipMaskDocumentSnapshot(
    String documentId,
    String relativePath,
    List<PsdLayerSnapshot> layers
) {
    public PsdClipMaskDocumentSnapshot {
        if (documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("documentId must not be null or blank");
        }
        if (relativePath == null || relativePath.isBlank() || relativePath.startsWith("/") || relativePath.contains("..")) {
            throw new IllegalArgumentException("relativePath must be relative and must not contain parent segments");
        }
        layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
    }

    @PreviewApi
    public record PsdLayerSnapshot(
        String layerId,
        String name,
        boolean visible,
        boolean clipping,
        List<ArtMeshId> artMeshIds,
        Optional<String> clippingBaseLayerId,
        List<PsdLayerSnapshot> children
    ) {
        public PsdLayerSnapshot {
            if (layerId == null || layerId.isBlank()) {
                throw new IllegalArgumentException("layerId must not be null or blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must not be null or blank");
            }
            artMeshIds = List.copyOf(Objects.requireNonNull(artMeshIds, "artMeshIds"));
            clippingBaseLayerId = Objects.requireNonNull(clippingBaseLayerId, "clippingBaseLayerId")
                .map(value -> {
                    if (value.isBlank()) throw new IllegalArgumentException("clippingBaseLayerId must not be blank");
                    return value;
                });
            if (!clipping && clippingBaseLayerId.isPresent()) {
                throw new IllegalArgumentException("a non-clipping layer must not declare a clipping base layer");
            }
            children = List.copyOf(Objects.requireNonNull(children, "children"));
        }

        /**
         * Convenience constructor deriving the explicit clipping state from the
         * presence of a clipping base layer (legacy behavior).
         */
        public PsdLayerSnapshot(
            final String layerId,
            final String name,
            final boolean visible,
            final List<ArtMeshId> artMeshIds,
            final Optional<String> clippingBaseLayerId,
            final List<PsdLayerSnapshot> children
        ) {
            this(layerId, name, visible, clippingBaseLayerId.isPresent(), artMeshIds, clippingBaseLayerId, children);
        }

        public PsdLayerSnapshot(final String layerId, final String name, final boolean visible) {
            this(layerId, name, visible, false, List.of(), Optional.empty(), List.of());
        }
    }
}
