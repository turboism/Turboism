package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

/**
 * Rotation freedom granted to a polygon packing backend.
 *
 * <p>Mirrors the Cubism 5.4 automatic-layout rotation setting:
 * {@code NONE} keeps each item's issued angle (one candidate), {@code QUARTER}
 * additionally tries 90-degree steps (four candidates), and {@code FREE} searches
 * arbitrary angles.</p>
 */
@CubismEditor
public enum TextureAtlasRotationMode {
    NONE,
    QUARTER,
    FREE
}
