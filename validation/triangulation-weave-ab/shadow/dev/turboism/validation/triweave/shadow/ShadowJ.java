package dev.turboism.validation.triweave.shadow;

/** Shadow analog of the official edge type j: two endpoint accessors, compared by index. */
public final class ShadowJ {
    private final ShadowPoint x, y;

    public ShadowJ(ShadowPoint x, ShadowPoint y) {
        this.x = x; this.y = y;
    }

    public ShadowPoint x() { return x; }
    public ShadowPoint y() { return y; }
}
