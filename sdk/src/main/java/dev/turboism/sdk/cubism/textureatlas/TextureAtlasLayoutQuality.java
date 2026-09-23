package dev.turboism.sdk.cubism.textureatlas;

/**
 * Time/quality trade-off preset for polygon packing.
 *
 * <p>{@code FAST} prefers speed over density (coarse outlines and few rotation
 * candidates), {@code DENSE} spends more time for tighter packing, and
 * {@code BALANCED} is the default middle ground.</p>
 */
public enum TextureAtlasLayoutQuality {
    FAST,
    BALANCED,
    DENSE
}
