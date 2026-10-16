package dev.turboism.plugin.frameanimation.domain;

/**
 * Alignment anchor point for frame mesh placement on canvas.
 */
public enum AnchorPoint {
    CENTER(0.5f, 0.5f),
    BOTTOM_CENTER(0.5f, 0.0f),
    TOP_CENTER(0.5f, 1.0f),
    TOP_LEFT(0.0f, 1.0f),
    BOTTOM_LEFT(0.0f, 0.0f);

    private final float u;
    private final float v;

    AnchorPoint(final float u, final float v) {
        this.u = u;
        this.v = v;
    }

    /** Horizontal anchor coordinate in [0, 1]. */
    public float anchorU() {
        return u;
    }

    /** Vertical anchor coordinate in [0, 1]. */
    public float anchorV() {
        return v;
    }
}
