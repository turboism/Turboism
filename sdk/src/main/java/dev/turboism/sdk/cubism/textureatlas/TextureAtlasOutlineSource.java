package dev.turboism.sdk.cubism.textureatlas;

/**
 * Where an item outline came from.
 *
 * <p>{@code DRAW_DATA_SHAPES} is the host model-image contour source (mesh/alpha
 * drawable shapes in material-local coordinates, the same source Cubism 5.4 feeds to
 * its polygon packer). {@code BOUNDS_FALLBACK} is the item bounding rectangle used
 * when the host exposes no contour for an item; {@code EXPLICIT} marks caller
 * supplied outlines.</p>
 */
public enum TextureAtlasOutlineSource {
    DRAW_DATA_SHAPES,
    BOUNDS_FALLBACK,
    EXPLICIT
}
