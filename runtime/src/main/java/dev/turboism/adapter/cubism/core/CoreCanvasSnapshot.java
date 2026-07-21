package dev.turboism.adapter.cubism.core;

/**
 * Adapter-owned scalar projection of Cubism Core canvas information.
 *
 * <p>No authoring metadata is inferred beyond the values exposed by Core itself.</p>
 */
record CoreCanvasSnapshot(
    float widthPixels,
    float heightPixels,
    float originXPixels,
    float originYPixels,
    float pixelsPerUnit
) {

    CoreCanvasSnapshot {
        requireFinite(widthPixels, "widthPixels");
        requireFinite(heightPixels, "heightPixels");
        requireFinite(originXPixels, "originXPixels");
        requireFinite(originYPixels, "originYPixels");
        requireFinite(pixelsPerUnit, "pixelsPerUnit");
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
