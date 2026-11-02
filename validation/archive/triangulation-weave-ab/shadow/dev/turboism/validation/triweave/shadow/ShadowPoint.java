package dev.turboism.validation.triweave.shadow;

/**
 * Shadow analog of the official TriPoint: immutable index after construction. The accessor
 * name differs from the official {@code getIndex()} on purpose — the whole shadow shape is
 * driven by the Config, not by hardcoded names.
 */
public final class ShadowPoint {
    public final float x, y;
    private final int index;

    public ShadowPoint(float x, float y, int index) {
        this.x = x; this.y = y; this.index = index;
    }

    public int index() { return index; }
}
