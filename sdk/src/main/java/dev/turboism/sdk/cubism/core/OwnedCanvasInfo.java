package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Canvas;

/**
 * Immutable adapter-owned canvas metrics of one owned Core model.
 *
 * <p>Values are copied from the Core {@code CubismCanvasInfo} surface at read time;
 * the projection never holds a Core object.</p>
 */
@PreviewApi
public record OwnedCanvasInfo(
    float widthPixels,
    float heightPixels,
    float originXPixels,
    float originYPixels,
    float pixelsPerUnit
) implements Canvas {

    public OwnedCanvasInfo {
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
