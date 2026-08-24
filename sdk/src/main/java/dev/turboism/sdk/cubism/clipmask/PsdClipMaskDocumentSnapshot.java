package dev.turboism.sdk.cubism.clipmask;

import dev.turboism.sdk.cubism.id.ArtMeshId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable PSD resource snapshot with the clipping relationship data needed
 * for clip-mask planning, associated with this Editor model.
 */
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

    /**
     * One PSD layer in the snapshot, including the layers nested beneath it.
     *
     * <p>Immutable and defensively copied: both list components are taken through
     * {@code List.copyOf}, so they are unmodifiable and reject {@code null} elements, and the
     * children form the layer tree recursively. {@code clipping} is the layer's own explicit
     * clipping flag and may be {@code true} even with no base layer recorded, but the reverse is
     * rejected — a non-clipping layer must not name a clipping base.
     *
     * @param layerId the PSD's own identifier for this layer; must not be {@code null} or blank
     * @param name the layer's user-visible name; must not be {@code null} or blank
     * @param visible whether the layer is visible in the PSD
     * @param clipping whether this layer clips to the layer below it
     * @param artMeshIds the art meshes in the Editor model bound to this layer, possibly empty;
     *     must not be {@code null}
     * @param clippingBaseLayerId the layer id this one clips to, empty when none is recorded; must
     *     not be {@code null}, must not hold a blank value, and must be empty when
     *     {@code clipping} is {@code false}
     * @param children the layers nested inside this one, possibly empty; must not be {@code null}
     */
    public record PsdLayerSnapshot(
        String layerId,
        String name,
        boolean visible,
        boolean clipping,
        List<ArtMeshId> artMeshIds,
        Optional<String> clippingBaseLayerId,
        List<PsdLayerSnapshot> children
    ) {
        /**
         * Validates the record components.
         *
         * @throws IllegalArgumentException if a required string is blank, or if a non-clipping layer
         *     declares a clipping base layer
         */
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

        /**
         * Convenience constructor for a leaf layer that neither clips nor binds to any art mesh.
         *
         * @param layerId the PSD's own identifier for this layer; must not be {@code null} or blank
         * @param name the layer's user-visible name; must not be {@code null} or blank
         * @param visible whether the layer is visible in the PSD
         */
        public PsdLayerSnapshot(final String layerId, final String name, final boolean visible) {
            this(layerId, name, visible, false, List.of(), Optional.empty(), List.of());
        }
    }
}
