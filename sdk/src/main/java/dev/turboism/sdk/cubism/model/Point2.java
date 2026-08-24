package dev.turboism.sdk.cubism.model;


/** Immutable two-dimensional point owned by Turboism. */
public record Point2(float x, float y) {

    public Point2 {
        requireFinite(x, "x");
        requireFinite(y, "y");
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
