package dev.turboism.sdk.cubism.textureatlas;

/**
 * Packing backend for a texture-atlas layout.
 *
 * <p>{@code AUTO} lets the planner pick a backend per input shape: near-rectangular
 * inputs take the rectangle path while genuinely irregular outlines take the polygon
 * path. {@code HOST_NATIVE} names the host editor's own packing engine; it is a
 * reserved capability slot - selecting it delegates to the host exactly like the
 * pass-through algorithm and produces no Turboism plan.</p>
 */
public enum TextureAtlasLayoutBackend {
    AUTO,
    MAXRECTS_RECTANGLE,
    DALSOO_POLYGON,
    HOST_NATIVE
}
