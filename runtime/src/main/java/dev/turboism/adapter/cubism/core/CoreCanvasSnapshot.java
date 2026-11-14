package dev.turboism.adapter.cubism.core;

import dev.turboism.internal.Requirements;

/**
 * Adapter-owned scalar projection of Cubism Core canvas information.
 *
 * <p>No authoring metadata is inferred beyond the values exposed by Core itself.</p>
 */
record CoreCanvasSnapshot(
        float widthPixels, float heightPixels, float originXPixels, float originYPixels, float pixelsPerUnit) {

    CoreCanvasSnapshot {
        Requirements.finite(widthPixels, "widthPixels");
        Requirements.finite(heightPixels, "heightPixels");
        Requirements.finite(originXPixels, "originXPixels");
        Requirements.finite(originYPixels, "originYPixels");
        Requirements.finite(pixelsPerUnit, "pixelsPerUnit");
    }
}
